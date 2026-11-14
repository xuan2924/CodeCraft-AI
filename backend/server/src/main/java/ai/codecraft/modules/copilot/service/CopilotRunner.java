package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.modelaccess.config.ModelCallProperties;
import ai.codecraft.modules.modelaccess.gateway.ModelCallDriver;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationLimitsDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Application-owned loop. Every phase is durably admitted before its external call, with no SDK retry loop. */
@Service
public class CopilotRunner {
    private final CopilotExecutionStore store;
    private final StorageCapacityService capacity;
    private final ModelCallDriver driver;
    private final CopilotReadTools tools;
    private final CopilotProperties limits;
    private final ModelCallProperties calls;
    private final IdentitySecrets secrets;
    private final ObjectMapper json;

    public CopilotRunner(CopilotExecutionStore store, StorageCapacityService capacity, ModelCallDriver driver,
        CopilotReadTools tools, CopilotProperties limits, ModelCallProperties calls,
        IdentitySecrets secrets, ObjectMapper json) {
        this.store = store; this.capacity = capacity; this.driver = driver;
        this.tools = tools; this.limits = limits; this.calls = calls; this.secrets = secrets; this.json = json;
    }

    public void run(String id, String worker) {
        var claimed = store.claim(id, worker);
        if (claimed == null) return;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var task = store.current(id, worker);
                if (task == null) return;
                if (task.stopRequested()) { store.finish(id, worker, "STOPPED", "USER_STOP"); return; }
                var dispatch = capacity.admit(task.sessionHash(), task.exerciseId(), measurement -> store.beginModel(id, worker, measurement));
                if (dispatch == null) continue;
                var target = dispatch.target();
                var request = dispatch.request();
                var result = driver.call(target, request, new ModelInvocationLimitsDTO(target.model().maxOutputTokens(), target.model().thinkingBudgetTokens()),
                    Math.min(limits.responseBytes(), calls.maxDecodedBytes()), dispatch.turn().notAfter(), new PartialCapture(id, worker, dispatch.turn().id()));
                var assistant = result.response().aiMessage();
                validate(assistant);
                if (!store.completeModel(id, worker, dispatch.turn().id(), ChatMessageSerializer.messageToJson(assistant), assistant.hasToolExecutionRequests(), result.response().tokenUsage())) return;
                if (!assistant.hasToolExecutionRequests()) {
                    var current = store.current(id, worker);
                    if (current != null) store.finish(id, worker, current.stopRequested() ? "STOPPED" : "SUCCEEDED", current.stopRequested() ? "USER_STOP" : null);
                    return;
                }
                var signature = new StringBuilder();
                for (int position = 0; position < assistant.toolExecutionRequests().size(); position++) {
                    var current = store.current(id, worker);
                    if (current == null) return;
                    if (current.stopRequested()) { store.finish(id, worker, "STOPPED", "USER_STOP"); return; }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Copilot worker interrupted");
                    var toolRequest = assistant.toolExecutionRequests().get(position);
                    var tool = capacity.admit(current.sessionHash(), current.exerciseId(), measurement -> store.beginTool(id, worker, dispatch.turn().id(), toolRequest, measurement));
                    if (tool == null) {
                        var stopped = store.current(id, worker);
                        if (stopped != null && stopped.stopRequested()) store.finish(id, worker, "STOPPED", "USER_STOP");
                        return;
                    }
                    var toolResult = tools.execute(current.sessionHash(), current.exerciseId(), tool.name(), tool.arguments());
                    String body = json.writeValueAsString(toolResult);
                    signature.append(tool.name()).append('\n').append(tool.arguments()).append('\n').append(body).append('\n');
                    if (!store.completeTool(id, worker, tool, ChatMessageSerializer.messageToJson(ToolExecutionResultMessage.from(toolRequest, body)),
                        position + 1 == assistant.toolExecutionRequests().size())) return;
                }
                if (!store.madeProgress(id, worker, secrets.fingerprint(signature.toString()))) {
                    store.finish(id, worker, "FAILED", "NO_PROGRESS"); return;
                }
            }
            interrupted(id, worker);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            interrupted(id, worker);
        } catch (BusinessException failure) {
            String code = failure.errorCode().code();
            boolean paused = Set.of("MODEL_SELECTION_REQUIRED", "MODEL_CONNECTION_NOT_FOUND", "MODEL_CONFIGURATION_NOT_FOUND", "MODEL_VERSION_CONFLICT",
                "MODEL_CREDENTIAL_UNAVAILABLE", "MODEL_RUNTIME_BUSY", "UNAUTHENTICATED", "PASSWORD_CHANGE_REQUIRED", "CONTROL_TRANSITION_PENDING",
                "STORAGE_BUDGET_EXCEEDED", "STORAGE_METER_UNAVAILABLE", CopilotErrorCode.COPILOT_CONTEXT_REQUIRED.code()).contains(code);
            store.finish(id, worker, paused ? "PAUSED" : "FAILED", code);
        } catch (JsonProcessingException invalid) {
            store.finish(id, worker, "FAILED", "TOOL_RESULT_ENCODING_FAILED");
        }
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
                store.partial(task, worker, turn, text.toString());
                persisted = text.length(); lastFlush = now;
            }
        }
    }

    private void validate(AiMessage message) {
        if (message == null || !message.hasToolExecutionRequests() && (message.text() == null || message.text().isBlank())) invalidResponse();
        if (message.toolExecutionRequests().size() > limits.batchTools()) invalidResponse();
        var ids = new HashSet<String>();
        for (var call : message.toolExecutionRequests()) {
            if (call.id() == null || call.id().isBlank() || call.id().length() > 256 || !ids.add(call.id()) || call.name() == null || call.name().isBlank()
                || call.name().length() > 128 || call.arguments() == null || call.arguments().getBytes(StandardCharsets.UTF_8).length > 4096) invalidResponse();
        }
    }

    private void invalidResponse() { throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_RESPONSE); }
}
