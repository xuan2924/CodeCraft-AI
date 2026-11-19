package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import ai.codecraft.modules.modelaccess.model.dto.ModelCallResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ai.codecraft.modules.copilot.model.dto.ToolOutcome;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.springframework.stereotype.Service;

/** Application-owned loop. Suspended batches resume their durable position, never regenerate their model response. */
@Service
public class CopilotRunner {
    private final CopilotExecutionStore store;
    private final StorageCapacityService capacity;
    private final ModelCallDriver driver;
    private final CopilotToolExecutor tools;
    private final CopilotProperties limits;
    private final ModelCallProperties calls;
    private final CopilotContextService contexts;
    private final TransientTaskStreamHub notifications;
    private final ObjectMapper json;

    public CopilotRunner(CopilotExecutionStore store, StorageCapacityService capacity, ModelCallDriver driver,
        CopilotToolExecutor tools, CopilotProperties limits, ModelCallProperties calls, CopilotContextService contexts,
        TransientTaskStreamHub notifications, ObjectMapper json) {
        this.store = store; this.capacity = capacity; this.driver = driver; this.tools = tools; this.limits = limits; this.calls = calls; this.contexts = contexts;
        this.notifications = notifications; this.json = json;
    }

    public void run(String id, String worker) {
        if (store.claim(id, worker) == null) return;
        try {
            loop: while (!Thread.currentThread().isInterrupted()) {
                var task = store.current(id, worker);
                if (task == null) return;
                if (task.stopRequested()) { store.finish(id, worker, "STOPPED", "USER_STOP"); return; }
                if (store.steerAtBoundary(id, worker)) continue;
                var turn = store.turn(id, worker);
                AiMessage assistant;
                if (turn != null && "TOOLS".equals(turn.state())) {
                    String retained = store.assistant(id, worker);
                    if (retained == null) return;
                    assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(retained);
                } else {
                    if (turn != null && "SUCCEEDED".equals(turn.state()) && !store.madeProgress(id, worker, turn.id())) {
                        store.finish(id, worker, "FAILED", "NO_PROGRESS"); return;
                    }
                    var prepared = contexts.beforeModel(id, worker);
                    if (prepared == null) continue;
                    var dispatch = capacity.admit(task.sessionHash(), task.exerciseId(), measurement -> store.beginModel(id, worker, prepared, measurement));
                    if (dispatch == null) continue;
                    var target = dispatch.target();
                    ModelCallResultDTO result;
                    try (var preview = new CopilotPartialCapture(id, dispatch.turn().id(), notifications, json,
                        (text, thinking) -> store.partial(id, worker, dispatch.turn().id(), text, thinking), limits.responseBytes())) {
                        result = driver.call(target, dispatch.request(), new ModelInvocationLimitsDTO(target.model().maxOutputTokens(), target.model().thinkingBudgetTokens()),
                            Math.min(limits.responseBytes(), calls.maxDecodedBytes()), dispatch.turn().notAfter(), preview::text, preview::thinking, preview::tool);
                    }
                    assistant = result.response().aiMessage();
                    validate(assistant);
                    if (!store.completeModel(id, worker, dispatch.turn().id(), ChatMessageSerializer.messageToJson(assistant), assistant.hasToolExecutionRequests(), result.response().tokenUsage())) return;
                    if (!assistant.hasToolExecutionRequests()) {
                        var current = store.current(id, worker);
                        if (current != null) store.finish(id, worker, current.stopRequested() ? "STOPPED" : "SUCCEEDED", current.stopRequested() ? "USER_STOP" : null);
                        return;
                    }
                    turn = store.turn(id, worker);
                    if (turn == null) return;
                }
                var batch = turn;
                for (int position = batch.toolPosition(); position < assistant.toolExecutionRequests().size(); position++) {
                    var current = store.current(id, worker);
                    if (current == null) return;
                    if (current.stopRequested()) { store.finish(id, worker, "STOPPED", "USER_STOP"); return; }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Copilot worker interrupted");
                    if (store.steerAtBoundary(id, worker)) continue loop;
                    var requests = assistant.toolExecutionRequests();
                    int exploreEnd = position;
                    while ("MAIN".equals(current.lane()) && exploreEnd < requests.size() && exploreEnd < position + 3
                        && tools.explore(requests.get(exploreEnd).name(), requests.get(exploreEnd).arguments())) exploreEnd++;
                    if (exploreEnd > position + 1) {
                        var window = requests.subList(position, exploreEnd);
                        var admitted = capacity.admit(current.sessionHash(), current.exerciseId(), measurement -> store.beginExploreTools(id, worker, batch.id(), window, measurement));
                        if (admitted.isEmpty()) {
                            if (retryAfterDenied(id, worker)) continue loop;
                            return;
                        }
                        var outcome = tools.executeExplorations(current, worker, admitted);
                        if (outcome.waiting()) return;
                        for (var tool : admitted) {
                            if (!store.completeTool(id, worker, tool, ChatMessageSerializer.messageToJson(ToolExecutionResultMessage.from(
                                requests.get(tool.position()), outcome.body())), tool.position() + 1 == requests.size())) return;
                        }
                        position = exploreEnd - 1;
                        continue;
                    }
                    int end = position;
                    while (end < requests.size() && end < position + 4) {
                        var entry = CopilotToolCatalog.find(requests.get(end).name());
                        if (entry == null || !entry.rereadable()) break;
                        end++;
                    }
                    if (end > position + 1) {
                        var window = requests.subList(position, end);
                        var admitted = capacity.admit(current.sessionHash(), current.exerciseId(), measurement -> store.beginReadTools(id, worker, batch.id(), window, measurement));
                        if (admitted.isEmpty()) {
                            if (retryAfterDenied(id, worker)) continue loop;
                            return;
                        }
                        if (!executeReads(current, worker, admitted, requests)) return;
                        position = end - 1;
                        continue;
                    }
                    var request = requests.get(position);
                    var tool = capacity.admit(current.sessionHash(), current.exerciseId(), measurement -> store.beginTool(id, worker, batch.id(), request, measurement));
                    if (tool == null) {
                        if (retryAfterDenied(id, worker)) continue loop;
                        return;
                    }
                    var outcome = tools.execute(current, worker, tool);
                    if (outcome.waiting()) return;
                    if (!store.completeTool(id, worker, tool, ChatMessageSerializer.messageToJson(ToolExecutionResultMessage.from(request, outcome.body())),
                        position + 1 == assistant.toolExecutionRequests().size())) return;
                }
            }
            interrupted(id, worker);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); interrupted(id, worker);
        } catch (BusinessException failure) {
            String code = failure.errorCode().code();
            var current = store.current(id, worker);
            if (current != null && current.stopRequested()) { store.finish(id, worker, "STOPPED", "USER_STOP"); return; }
            boolean paused = Set.of("MODEL_SELECTION_REQUIRED", "MODEL_CONNECTION_NOT_FOUND", "MODEL_CONFIGURATION_NOT_FOUND", "MODEL_VERSION_CONFLICT",
                "MODEL_CREDENTIAL_UNAVAILABLE", "MODEL_RUNTIME_BUSY", "UNAUTHENTICATED", "PASSWORD_CHANGE_REQUIRED", "CONTROL_TRANSITION_PENDING",
                "STORAGE_BUDGET_EXCEEDED", "STORAGE_METER_UNAVAILABLE", CopilotErrorCode.COPILOT_CONTEXT_REQUIRED.code()).contains(code);
            store.finish(id, worker, paused ? "PAUSED" : "FAILED", code);
        } catch (JsonProcessingException invalid) { store.finish(id, worker, "FAILED", "TOOL_RESULT_ENCODING_FAILED"); }
    }

    private boolean retryAfterDenied(String id, String worker) {
        if (store.steerAtBoundary(id, worker)) return true;
        var current = store.current(id, worker);
        if (current != null && current.stopRequested()) store.finish(id, worker, "STOPPED", "USER_STOP");
        return false;
    }

    private boolean executeReads(CopilotTaskEntity task, String worker, List<CopilotToolEntity> admitted, List<ToolExecutionRequest> requests)
        throws InterruptedException, JsonProcessingException {
        var futures = new ArrayList<Future<ToolOutcome>>();
        // One admitted window is a safe-point unit: steer is applied after it drains, before any subsequent tool.
        try (var executor = Executors.newFixedThreadPool(4, Thread.ofVirtual().name("copilot-read-", 0).factory())) {
            try {
                for (var tool : admitted) futures.add(executor.submit(() -> tools.execute(task, worker, tool)));
                for (int index = 0; index < admitted.size(); index++) {
                    ToolOutcome outcome;
                    while (true) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Copilot read window interrupted");
                        var current = store.current(task.id(), worker);
                        if (current == null) return false;
                        if (current.stopRequested()) { store.finish(task.id(), worker, "STOPPED", "USER_STOP"); return false; }
                        try { outcome = futures.get(index).get(100, TimeUnit.MILLISECONDS); break; }
                        catch (TimeoutException pending) { /* Poll ownership and stop even when the first result is slow. */ }
                    }
                    if (outcome.waiting()) throw new IllegalStateException("Read-only tool suspended");
                    var tool = admitted.get(index);
                    if (!store.completeTool(task.id(), worker, tool, ChatMessageSerializer.messageToJson(
                        ToolExecutionResultMessage.from(requests.get(tool.position()), outcome.body())), tool.position() + 1 == requests.size())) return false;
                }
                return true;
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof InterruptedException interrupted) throw interrupted;
                if (failure.getCause() instanceof JsonProcessingException invalid) throw invalid;
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Read tool failed", failure.getCause());
            } finally {
                for (var future : futures) future.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    private void interrupted(String id, String worker) {
        boolean wasInterrupted = Thread.interrupted();
        try {
            var task = store.current(id, worker);
            if (task != null) store.finish(id, worker, task.stopRequested() ? "STOPPED" : "INTERRUPTED", task.stopRequested() ? "USER_STOP" : store.interruptionReason(id, worker));
        } finally { if (wasInterrupted) Thread.currentThread().interrupt(); }
    }
    private void validate(AiMessage message) {
        if (message == null || !message.hasToolExecutionRequests() && (message.text() == null || message.text().isBlank())) invalidResponse();
        if (message.toolExecutionRequests().size() > limits.batchTools()) invalidResponse();
        var ids = new HashSet<String>();
        for (var call : message.toolExecutionRequests()) {
            if (call.id() == null || call.id().isBlank() || call.id().length() > 256 || !ids.add(call.id()) || call.name() == null || call.name().isBlank()
                || call.name().length() > 128 || call.arguments() == null) invalidResponse();
            var definition = CopilotToolCatalog.find(call.name());
            int argumentLimit = definition == null ? 4096 : definition.maxArgBytes();
            if (call.arguments().getBytes(StandardCharsets.UTF_8).length > argumentLimit) invalidResponse();
        }
    }
    private void invalidResponse() { throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_RESPONSE); }
}
