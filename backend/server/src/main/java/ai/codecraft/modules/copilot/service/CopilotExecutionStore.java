package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotModelDispatchDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTurnEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.nio.charset.StandardCharsets;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import dev.langchain4j.model.output.TokenUsage;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable dispatch markers are committed before any model/network IO. */
@Service
public class CopilotExecutionStore {
    private final ObjectMapper evidenceJson = JsonMapper.builder().visibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
        .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY).build();
    private static final long MODEL_CREDIT = 2 * 1024 * 1024;
    private final CopilotTaskRepository tasks;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore workspace;
    private final ExerciseModelService models;
    private final WorkspaceAiOperationService operations;
    private final CopilotProperties limits;
    private final Clock clock;
    private final CopilotRequestFactory requests;

    public CopilotExecutionStore(CopilotTaskRepository tasks, IdentityTransactionService identity,
        WorkspaceCoordinationStore workspace, ExerciseModelService models, WorkspaceAiOperationService operations,
        CopilotProperties limits, Clock clock, CopilotRequestFactory requests) {
        this.tasks = tasks; this.identity = identity; this.workspace = workspace; this.models = models;
        this.operations = operations; this.limits = limits; this.clock = clock; this.requests = requests;
    }

    @Transactional
    public CopilotTaskEntity claim(String id, String worker) {
        var task = tasks.find(id);
        if (task == null) return null;
        identity.lockResourceOwner(task.userId());
        if (tasks.claim(id, worker, clock.instant().plus(limits.workerLease())) != 1) return null;
        return tasks.find(id);
    }

    @Transactional
    public CopilotModelDispatchDTO beginModel(String id, String worker, StorageMeasurement measurement) {
        var task = owned(id, worker);
        if (task == null || task.stopRequested()) return null;
        var previous = tasks.latestTurn(id);
        if (previous != null && !("SUCCEEDED".equals(previous.state()) || "INTERRUPTED".equals(previous.state()) || "FAILED".equals(previous.state()))) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        if (tasks.contextBytes(task.exerciseId()) > limits.contextBytes()) throw new BusinessException(CopilotErrorCode.COPILOT_CONTEXT_REQUIRED);
        var grant = identity.authorizeMutation(task.sessionHash());
        var target = models.resolveForCall(task.sessionHash(), task.exerciseId());
        var exercise = workspace.exercise(task.exerciseId());
        Instant notAfter = clock.instant().plus(Duration.ofSeconds(120));
        if (exercise.deadlineAt().isBefore(notAfter)) notAfter = exercise.deadlineAt();
        if (grant.expiresAt().isBefore(notAfter)) notAfter = grant.expiresAt();
        var turn = new CopilotTurnEntity(UUID.randomUUID().toString(), id, task.exerciseId(), previous == null ? 1 : previous.ordinal() + 1,
            "DISPATCHED", target.connection().id(), target.model().id(), Long.parseLong(target.connection().configurationVersion()),
            Long.parseLong(target.model().configurationVersion()), target.connection().protocol().name(), target.model().modelId(),
            target.model().contextWindow(), null, 0, clock.instant(), notAfter, null, null);
        operations.reserve(task.sessionHash(), task.exerciseId(), turn.id(), "COPILOT_MODEL", measurement, MODEL_CREDIT);
        boolean converted = tasks.foreignHistory(task.exerciseId(), turn.connectionId(), turn.modelConfigurationId(), turn.connectionConfigurationVersion(), turn.modelConfigurationVersion());
        var request = requests.create(target, tasks.context(task.exerciseId()).stream().map(CopilotMessageEntity::payload).toList(), converted);
        tasks.turn(turn);
        tasks.configuration(turn.id(), evidencePayload(Map.of("connection", target.connection(), "model", target.model())));
        tasks.historyMode(turn.id(), converted);
        tasks.renew(id, worker, clock.instant().plus(limits.workerLease()));
        return new CopilotModelDispatchDTO(turn, target, request, converted);
    }

    @Transactional
    public void partial(String id, String worker, String turnId, String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > limits.responseBytes()) throw new IllegalArgumentException("Partial response exceeds reserved storage");
        if (owned(id, worker) == null) return;
        var turn = tasks.latestTurn(id);
        if (turn != null && turn.id().equals(turnId)) tasks.partialText(turnId, text);
    }

    @Transactional
    public boolean completeModel(String id, String worker, String turnId, String payload, boolean tools, TokenUsage usage) {
        var task = owned(id, worker);
        if (task == null) return false;
        var turn = tasks.latestTurn(id);
        if (turn == null || !turn.id().equals(turnId) || !"DISPATCHED".equals(turn.state())) return false;
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > limits.responseBytes()) throw new IllegalArgumentException("Model payload exceeds reserved storage");
        String message = UUID.randomUUID().toString();
        Long input = usage == null || usage.inputTokenCount() == null ? null : usage.inputTokenCount().longValue();
        Long output = usage == null || usage.outputTokenCount() == null ? null : usage.outputTokenCount().longValue();
        Long total = usage == null || usage.totalTokenCount() == null ? null : usage.totalTokenCount().longValue();
        tasks.message(new CopilotMessageEntity(0, message, id, task.exerciseId(), "ASSISTANT", payload, clock.instant()));
        if (tasks.response(turnId, tools ? "TOOLS" : "SUCCEEDED", message, input, output, total, input == null || output == null, evidencePayload(usage), clock.instant()) != 1) {
            throw new IllegalStateException("Model response lost its dispatch marker");
        }
        // Keep the batch's emergency pairing credit until every tool has a durable result.
        if (!tools) operations.settle(task.exerciseId(), turnId, true);
        tasks.renew(id, worker, clock.instant().plus(limits.workerLease()));
        return true;
    }

    @Transactional
    public CopilotTurnEntity turn(String id, String worker) {
        return owned(id, worker) == null ? null : tasks.latestTurn(id);
    }

    @Transactional
    public String assistant(String id, String worker) {
        var task = owned(id, worker);
        if (task == null) return null;
        var turn = tasks.latestTurn(id);
        return turn == null || turn.assistantMessageId() == null ? null : tasks.messageById(turn.assistantMessageId()).payload();
    }

    @Transactional
    public CopilotToolEntity beginTool(String id, String worker, String turnId, ToolExecutionRequest request, StorageMeasurement measurement) {
        var task = owned(id, worker);
        if (task == null || task.stopRequested()) return null;
        var turn = tasks.latestTurn(id);
        if (turn == null || !turn.id().equals(turnId) || !"TOOLS".equals(turn.state())) return null;
        var assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(tasks.messageById(turn.assistantMessageId()).payload());
        if (!assistant.toolExecutionRequests().get(turn.toolPosition()).equals(request)) throw new IllegalStateException("Tool no longer matches its durable batch");
        if (tasks.toolAt(turnId, turn.toolPosition()) != null) return null;
        String operation = UUID.randomUUID().toString();
        operations.reserve(task.sessionHash(), task.exerciseId(), operation, "COPILOT_READ", measurement, 512 * 1024);
        var tool = new CopilotToolEntity(operation, turnId, id, task.exerciseId(), turn.toolPosition(), request.id(), request.name(), request.arguments(), "RUNNING", null, clock.instant(), null);
        tasks.tool(tool);
        tasks.renew(id, worker, clock.instant().plus(limits.workerLease()));
        return tool;
    }

    @Transactional
    public boolean completeTool(String id, String worker, CopilotToolEntity tool, String payload, boolean last) {
        var task = owned(id, worker);
        if (task == null) return false;
        var turn = tasks.latestTurn(id);
        if (turn == null || !turn.id().equals(tool.turnId()) || !"TOOLS".equals(turn.state()) || turn.toolPosition() != tool.position()) return false;
        if (payload.getBytes(StandardCharsets.UTF_8).length > 480 * 1024) throw new IllegalArgumentException("Tool payload exceeds reserved storage");
        String message = UUID.randomUUID().toString();
        if (tasks.completeTool(tool.id(), "SUCCEEDED", message, clock.instant()) != 1) return false;
        tasks.message(new CopilotMessageEntity(0, message, id, task.exerciseId(), "TOOL", payload, clock.instant()));
        if (tasks.advanceTool(turn.id(), tool.position(), last) != 1) throw new IllegalStateException("Tool checkpoint did not advance");
        operations.settle(task.exerciseId(), tool.id(), true);
        if (last) operations.settle(task.exerciseId(), turn.id(), true);
        tasks.renew(id, worker, clock.instant().plus(limits.workerLease()));
        return true;
    }

    /** Only read tools exist in this milestone. Future write tools must reconcile before entering this path. */
    private void interruptBatch(CopilotTaskEntity task, CopilotTurnEntity turn, String reason) {
        var assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(tasks.messageById(turn.assistantMessageId()).payload());
        for (int position = turn.toolPosition(); position < assistant.toolExecutionRequests().size(); position++) {
            var request = assistant.toolExecutionRequests().get(position);
            String message = UUID.randomUUID().toString();
            var existing = tasks.toolAt(turn.id(), position);
            String result = existing == null ? "{\"code\":\"NOT_EXECUTED\"}" : "{\"code\":\"INTERRUPTED\"}";
            tasks.message(new CopilotMessageEntity(0, message, task.id(), task.exerciseId(), "TOOL",
                ChatMessageSerializer.messageToJson(ToolExecutionResultMessage.from(request, result)), clock.instant()));
            if (existing != null && "RUNNING".equals(existing.state())) {
                tasks.completeTool(existing.id(), "INTERRUPTED", message, clock.instant());
                operations.settle(task.exerciseId(), existing.id(), false);
            }
        }
        tasks.failTurn(turn.id(), "INTERRUPTED", reason, clock.instant());
        operations.settle(task.exerciseId(), turn.id(), false);
    }

    @Transactional
    public boolean madeProgress(String id, String worker, String signature) {
        if (owned(id, worker) == null) return false;
        tasks.progress(id, signature);
        return tasks.noProgress(id) < limits.noProgressLimit();
    }

    @Transactional
    public CopilotTaskEntity current(String id, String worker) { return owned(id, worker); }

    @Transactional
    public void finish(String id, String worker, String state, String reason) {
        var task = owned(id, worker);
        if (task == null) return;
        var turn = tasks.latestTurn(id);
        if (turn != null && "DISPATCHED".equals(turn.state())) {
            tasks.failTurn(turn.id(), "FAILED", reason, clock.instant());
            operations.settle(task.exerciseId(), turn.id(), false);
        }
        if (turn != null && "TOOLS".equals(turn.state())) interruptBatch(task, turn, reason);
        tasks.finish(id, worker, state, reason, "PAUSED".equals(state) ? null : clock.instant());
    }

    @Transactional
    public void recover(String id) {
        var initial = tasks.find(id);
        if (initial == null) return;
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(id);
        if (task == null) return;
        tasks.checked(id, clock.instant());
        if ("QUEUED".equals(task.state()) || "PAUSED".equals(task.state())) {
            var exercise = workspace.exercise(task.exerciseId());
            if (exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt())) {
                tasks.expireIdle(id, "EXERCISE_ENDED", clock.instant());
            }
            return;
        }
        if (task.workerId() == null || task.workerNotAfter() == null || clock.instant().isBefore(task.workerNotAfter())) return;
        var turn = tasks.latestTurn(id);
        if (turn != null && "DISPATCHED".equals(turn.state())) {
            tasks.failTurn(turn.id(), "INTERRUPTED", "MODEL_RESULT_UNKNOWN", clock.instant());
            operations.settle(task.exerciseId(), turn.id(), false);
        }
        if (turn != null && "TOOLS".equals(turn.state())) interruptBatch(task, turn, "SERVICE_INTERRUPTED");
        tasks.finish(id, task.workerId(), task.stopRequested() ? "STOPPED" : "INTERRUPTED", "SERVICE_INTERRUPTED", clock.instant());
    }

    private String evidencePayload(Object value) {
        if (value == null) return null;
        try {
            String payload = evidenceJson.writeValueAsString(value);
            if (payload.getBytes(StandardCharsets.UTF_8).length > 32768) throw new IllegalStateException("Model metadata exceeds reserved storage");
            return payload;
        } catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot preserve model metadata", failure); }
    }

    private CopilotTaskEntity owned(String id, String worker) {
        var initial = tasks.find(id);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(id);
        return task != null && worker.equals(task.workerId()) && task.workerNotAfter() != null
            && clock.instant().isBefore(task.workerNotAfter()) ? task : null;
    }
}
