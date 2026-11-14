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
import ai.codecraft.modules.copilot.repository.CopilotProposalRepository;
import ai.codecraft.modules.identity.service.IdentitySecrets;
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
import java.util.Set;
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
    private final CopilotProposalRepository proposals;
    private final IdentitySecrets secrets;

    public CopilotExecutionStore(CopilotTaskRepository tasks, IdentityTransactionService identity,
        WorkspaceCoordinationStore workspace, ExerciseModelService models, WorkspaceAiOperationService operations,
        CopilotProperties limits, Clock clock, CopilotRequestFactory requests, CopilotProposalRepository proposals, IdentitySecrets secrets) {
        this.tasks = tasks; this.identity = identity; this.workspace = workspace; this.models = models;
        this.operations = operations; this.limits = limits; this.clock = clock; this.requests = requests; this.proposals = proposals; this.secrets = secrets;
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
        boolean proposal = "propose_changes".equals(request.name());
        operations.reserve(task.sessionHash(), task.exerciseId(), operation, proposal ? "COPILOT_PROPOSAL" : "COPILOT_READ", measurement,
            proposal ? CopilotProposalPreparation.PREPARATION_CREDIT : 512 * 1024);
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
        return recordTool(task, turn, tool, payload, last);
    }

    @Transactional
    public void parkProposal(String id, String worker, String toolId) {
        var task = owned(id, worker);
        var tool = tasks.toolById(toolId);
        if (task == null || task.stopRequested() || tool == null || !id.equals(tool.taskId()) || !"RUNNING".equals(tool.state())) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        }
        if (tasks.waitTool(toolId, "WAITING_APPROVAL") != 1 || tasks.park(id, worker, "WAITING_APPROVAL", "APPROVAL_REQUIRED") != 1) {
            throw new IllegalStateException("Proposal did not acquire its durable wait state");
        }
        operations.settle(task.exerciseId(), toolId, true);
    }

    @Transactional
    public void awaitWrite(String id, String toolId) {
        var initial = tasks.find(id);
        if (initial == null) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(id);
        var tool = tasks.toolById(toolId);
        if (task.stopRequested() || task.workerId() != null || !"WAITING_APPROVAL".equals(task.state()) || tool == null || !id.equals(tool.taskId())
            || tasks.waitTool(toolId, "APPLYING") != 1 || tasks.waitFor(id, "WAITING_WRITE", "WRITE_RECONCILIATION") != 1) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        }
    }

    /** Called in the same transaction that makes the proposal outcome final. */
    @Transactional
    public void resolveProposal(String id, String toolId, String body) {
        var initial = tasks.find(id);
        if (initial == null) throw new IllegalStateException("Proposal task is missing");
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(id);
        var tool = tasks.toolById(toolId);
        var turn = tasks.latestTurn(id);
        if (task.workerId() != null || proposals.applying(id) || tool == null || turn == null || !id.equals(tool.taskId())
            || !turn.id().equals(tool.turnId()) || turn.toolPosition() != tool.position() || !"TOOLS".equals(turn.state())) {
            throw new IllegalStateException("Proposal no longer matches its pending batch");
        }
        var assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(tasks.messageById(turn.assistantMessageId()).payload());
        var request = assistant.toolExecutionRequests().get(tool.position());
        if (!request.id().equals(tool.callId()) || !request.name().equals(tool.name()) || !request.arguments().equals(tool.arguments())) {
            throw new IllegalStateException("Proposal tool identity changed");
        }
        if (!recordTool(task, turn, tool, ChatMessageSerializer.messageToJson(ToolExecutionResultMessage.from(request, body)),
            tool.position() + 1 == assistant.toolExecutionRequests().size())) throw new IllegalStateException("Proposal result did not advance");
        tasks.wake(id);
    }

    private boolean recordTool(CopilotTaskEntity task, CopilotTurnEntity turn, CopilotToolEntity tool, String payload, boolean last) {
        if (payload.getBytes(StandardCharsets.UTF_8).length > 480 * 1024) throw new IllegalArgumentException("Tool payload exceeds reserved storage");
        String message = UUID.randomUUID().toString();
        if (tasks.completeTool(tool.id(), "SUCCEEDED", message, clock.instant()) != 1) return false;
        tasks.message(new CopilotMessageEntity(0, message, task.id(), task.exerciseId(), "TOOL", payload, clock.instant()));
        String previous = tasks.batchSignature(turn.id());
        var result = (ToolExecutionResultMessage) ChatMessageDeserializer.messageFromJson(payload);
        tasks.batchProgress(turn.id(), secrets.fingerprint((previous == null ? "" : previous) + "\n" + tool.name() + "\n" + tool.arguments() + "\n" + result.text()));
        if (tasks.advanceTool(turn.id(), tool.position(), last) != 1) throw new IllegalStateException("Tool checkpoint did not advance");
        operations.settle(task.exerciseId(), tool.id(), true);
        if (last) operations.settle(task.exerciseId(), turn.id(), true);
        if (task.workerId() != null) tasks.renew(task.id(), task.workerId(), clock.instant().plus(limits.workerLease()));
        return true;
    }

    /** Unknown writes must be reconciled before remaining protocol results can be synthesized. */
    private void interruptBatch(CopilotTaskEntity task, CopilotTurnEntity turn, String reason) {
        if (proposals.applying(task.id())) throw new IllegalStateException("Unconfirmed proposal write cannot be interrupted");
        proposals.cancelPending(task.id(), reason, clock.instant());
        var assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(tasks.messageById(turn.assistantMessageId()).payload());
        for (int position = turn.toolPosition(); position < assistant.toolExecutionRequests().size(); position++) {
            var request = assistant.toolExecutionRequests().get(position);
            String message = UUID.randomUUID().toString();
            var existing = tasks.toolAt(turn.id(), position);
            String result = existing == null || "WAITING_APPROVAL".equals(existing.state()) ? "{\"code\":\"NOT_EXECUTED\"}" : "{\"code\":\"INTERRUPTED\"}";
            tasks.message(new CopilotMessageEntity(0, message, task.id(), task.exerciseId(), "TOOL",
                ChatMessageSerializer.messageToJson(ToolExecutionResultMessage.from(request, result)), clock.instant()));
            if (existing != null && ("RUNNING".equals(existing.state()) || "WAITING_APPROVAL".equals(existing.state()))) {
                tasks.completeTool(existing.id(), "INTERRUPTED", message, clock.instant());
                operations.settle(task.exerciseId(), existing.id(), false);
            }
        }
        tasks.failTurn(turn.id(), "INTERRUPTED", reason, clock.instant());
        operations.settle(task.exerciseId(), turn.id(), false);
    }

    @Transactional
    public boolean madeProgress(String id, String worker, String turnId) {
        if (owned(id, worker) == null) return false;
        var turn = tasks.latestTurn(id);
        if (turn == null || !turnId.equals(turn.id()) || !"SUCCEEDED".equals(turn.state())) throw new IllegalStateException("Batch is not complete");
        String signature = tasks.batchSignature(turnId);
        if (signature != null && tasks.recordProgress(turnId) == 1) tasks.progress(id, signature);
        return tasks.noProgress(id) < limits.noProgressLimit();
    }

    @Transactional
    public CopilotTaskEntity current(String id, String worker) { return owned(id, worker); }

    @Transactional
    public void finish(String id, String worker, String state, String reason) {
        var task = owned(id, worker);
        if (task == null || proposals.applying(id)) return;
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
        if (task.workerId() == null) { recoverIdle(task); return; }
        if (task.workerId() == null || task.workerNotAfter() == null || clock.instant().isBefore(task.workerNotAfter())) return;
        if (proposals.applying(id)) return;
        var turn = tasks.latestTurn(id);
        if (turn != null && "DISPATCHED".equals(turn.state())) {
            tasks.failTurn(turn.id(), "INTERRUPTED", "MODEL_RESULT_UNKNOWN", clock.instant());
            operations.settle(task.exerciseId(), turn.id(), false);
        }
        if (turn != null && "TOOLS".equals(turn.state())) interruptBatch(task, turn, "SERVICE_INTERRUPTED");
        tasks.finish(id, task.workerId(), task.stopRequested() ? "STOPPED" : "INTERRUPTED", "SERVICE_INTERRUPTED", clock.instant());
    }

    private void recoverIdle(CopilotTaskEntity task) {
        if (!Set.of("QUEUED", "PAUSED", "WAITING_APPROVAL", "WAITING_WRITE", "STOPPING").contains(task.state())) return;
        var exercise = workspace.exercise(task.exerciseId());
        boolean ended = exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt());
        if (!task.stopRequested() && !ended) return;
        if (!task.stopRequested()) {
            tasks.haltIdle(task.id(), "EXERCISE_ENDED");
            task = tasks.find(task.id());
        }
        var pending = proposals.pending(task.id());
        if (pending != null && "APPLYING".equals(pending.state())) {
            var outcome = operations.mutationOutcome(task.exerciseId(), pending.operationId());
            if ("PENDING".equals(outcome.state()) || "PENDING_RECONCILIATION".equals(outcome.state())) workspace.reconcile(task.exerciseId());
            return;
        }
        String reason = "EXERCISE_ENDED".equals(task.reason()) ? "EXERCISE_ENDED" : "USER_STOP";
        var turn = tasks.latestTurn(task.id());
        if (turn != null && "TOOLS".equals(turn.state())) interruptBatch(task, turn, reason);
        tasks.finishIdle(task.id(), "EXERCISE_ENDED".equals(reason) ? "INTERRUPTED" : "STOPPED", reason, clock.instant());
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
