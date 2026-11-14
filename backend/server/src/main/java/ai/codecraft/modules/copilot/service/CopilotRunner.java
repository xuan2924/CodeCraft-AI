package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
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
import java.util.function.Consumer;
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

    public CopilotRunner(CopilotExecutionStore store, StorageCapacityService capacity, ModelCallDriver driver,
        CopilotToolExecutor tools, CopilotProperties limits, ModelCallProperties calls) {
        this.store = store; this.capacity = capacity; this.driver = driver; this.tools = tools; this.limits = limits; this.calls = calls;
    }

    public void run(String id, String worker) {
        if (store.claim(id, worker) == null) return;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var task = store.current(id, worker);
                if (task == null) return;
                if (task.stopRequested()) { store.finish(id, worker, "STOPPED", "USER_STOP"); return; }
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
                    var dispatch = capacity.admit(task.sessionHash(), task.exerciseId(), measurement -> store.beginModel(id, worker, measurement));
                    if (dispatch == null) continue;
                    var target = dispatch.target();
                    var result = driver.call(target, dispatch.request(), new ModelInvocationLimitsDTO(target.model().maxOutputTokens(), target.model().thinkingBudgetTokens()),
                        Math.min(limits.responseBytes(), calls.maxDecodedBytes()), dispatch.turn().notAfter(), new PartialCapture(id, worker, dispatch.turn().id()));
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
                    var request = assistant.toolExecutionRequests().get(position);
                    var tool = capacity.admit(current.sessionHash(), current.exerciseId(), measurement -> store.beginTool(id, worker, batch.id(), request, measurement));
                    if (tool == null) {
                        var stopped = store.current(id, worker);
                        if (stopped != null && stopped.stopRequested()) store.finish(id, worker, "STOPPED", "USER_STOP");
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

    private void interrupted(String id, String worker) {
        boolean wasInterrupted = Thread.interrupted();
        try {
            var task = store.current(id, worker);
            if (task != null) store.finish(id, worker, task.stopRequested() ? "STOPPED" : "INTERRUPTED", task.stopRequested() ? "USER_STOP" : "SERVICE_INTERRUPTED");
        } finally { if (wasInterrupted) Thread.currentThread().interrupt(); }
    }
    private final class PartialCapture implements Consumer<String> {
        private final String task;
        private final String worker;
        private final String turn;
        private final StringBuilder text = new StringBuilder();
        private int persisted;
        private long lastFlush;
        PartialCapture(String task, String worker, String turn) { this.task = task; this.worker = worker; this.turn = turn; }
        @Override public void accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            text.append(chunk);
            long now = System.nanoTime();
            if (lastFlush == 0 || text.length() - persisted >= 2048 || now - lastFlush >= 200_000_000L) {
                store.partial(task, worker, turn, text.toString()); persisted = text.length(); lastFlush = now;
            }
        }
    }
    private void validate(AiMessage message) {
        if (message == null || !message.hasToolExecutionRequests() && (message.text() == null || message.text().isBlank())) invalidResponse();
        if (message.toolExecutionRequests().size() > limits.batchTools()) invalidResponse();
        var ids = new HashSet<String>();
        for (var call : message.toolExecutionRequests()) {
            int argumentLimit = "propose_changes".equals(call.name()) ? CopilotProposalPreparation.MAX_ARGUMENT_BYTES : 4096;
            if (call.id() == null || call.id().isBlank() || call.id().length() > 256 || !ids.add(call.id()) || call.name() == null || call.name().isBlank()
                || call.name().length() > 128 || call.arguments() == null || call.arguments().getBytes(StandardCharsets.UTF_8).length > argumentLimit) invalidResponse();
        }
    }
    private void invalidResponse() { throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_RESPONSE); }
}
