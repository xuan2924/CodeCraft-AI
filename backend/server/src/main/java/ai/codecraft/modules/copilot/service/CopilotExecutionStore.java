package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotModelDispatchDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotContextTicketDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotContextPreparedDTO;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotSteerEntity;
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
import ai.codecraft.modules.copilot.repository.CopilotSteerRepository;
import ai.codecraft.modules.copilot.repository.CopilotProposalRepository;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import ai.codecraft.modules.skills.service.SkillSnapshots;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Durable dispatch markers are committed before any model/network IO. */
@Service
public class CopilotExecutionStore {
    private final CopilotCallEvidence evidence;
    private static final long MODEL_CREDIT = 2 * 1024 * 1024;
    private final CopilotTaskRepository tasks;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore workspace;
    private final ExerciseModelService models;
    private final WorkspaceAiOperationService operations;
    private final CopilotProperties limits;
    private final Clock clock;
    private final CopilotCompactionRepository contexts;
    private final CopilotProposalRepository proposals;
    private final IdentitySecrets secrets;
    private final CopilotTestLifecycle tests;
    private final CopilotCommandLifecycle commands;
    private final CopilotSteerRepository steers;
    private final SkillSnapshots skills;

    public CopilotExecutionStore(CopilotTaskRepository tasks, IdentityTransactionService identity,
        WorkspaceCoordinationStore workspace, ExerciseModelService models, WorkspaceAiOperationService operations,
        CopilotProperties limits, Clock clock, CopilotCompactionRepository contexts, CopilotProposalRepository proposals, IdentitySecrets secrets, CopilotTestLifecycle tests, CopilotSteerRepository steers, CopilotCallEvidence evidence, SkillSnapshots skills, CopilotCommandLifecycle commands) {
        this.commands = commands;
        this.tasks = tasks; this.identity = identity; this.workspace = workspace; this.models = models;
        this.operations = operations; this.limits = limits; this.clock = clock; this.contexts = contexts; this.proposals = proposals; this.secrets = secrets; this.tests = tests; this.steers = steers; this.evidence = evidence; this.skills = skills;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void steerIdle(String id) {
        var initial = tasks.find(id);
        if (initial == null) return;
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(id);
        if (task != null && task.workerId() == null && supersedeOldBatch(task)) tasks.wake(id);
    }

    @Transactional
    public boolean steerAtBoundary(String id, String worker) {
        var task = owned(id, worker);
        return task != null && supersedeOldBatch(task);
    }

    private boolean unsettledEffects(String task) { return proposals.applying(task) || tests.waiting(task) || commands.waiting(task); }

    private boolean supersedeOldBatch(CopilotTaskEntity task) {
        if (task.stopRequested() || !steers.unstaged(task.id()) || unsettledEffects(task.id())) return false;
        var turn = tasks.latestTurn(task.id());
        if (turn == null || !"TOOLS".equals(turn.state())) return false;
        var current = tasks.toolAt(turn.id(), turn.toolPosition());
        if (current != null && "RUNNING".equals(current.state())) return false;
        interruptBatch(task, turn, "STEER_SUPERSEDED");
        return true;
    }

    private void unappliedSteers(CopilotTaskEntity task, String reason) {
        for (String steer : steers.pendingIds(task.id())) operations.settle(task.exerciseId(), steer, false);
        steers.notApplied(task.id(), reason == null ? "TASK_FINISHED" : reason, clock.instant());
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
    public CopilotContextTicketDTO contextTicket(String id, String worker) {
        var task = owned(id, worker);
        if (task == null || task.stopRequested()) return null;
        var previous = tasks.latestTurn(id);
        if (previous != null && ("DISPATCHED".equals(previous.state()) || "TOOLS".equals(previous.state()))) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        var target = models.resolveForCall(task.sessionHash(), task.exerciseId());
        var head = contexts.head(task.conversationId());
        long through = head == null ? 0 : head.throughSequence();
        long convertedThrough = contexts.foreignThrough(task.conversationId(), through, target.connection().id(), target.model().id(),
            Long.parseLong(target.connection().configurationVersion()), Long.parseLong(target.model().configurationVersion()));
        return new CopilotContextTicketDTO(id, task.exerciseId(), task.sessionHash(), worker, head == null ? 0 : head.version(),
            head == null ? null : head.summaryId(), through, tasks.lastSequence(task.conversationId()), target, steers.pendingIds(id), convertedThrough, task.conversationId());
    }

    @Transactional
    public CopilotModelDispatchDTO beginModel(String id, String worker, CopilotContextPreparedDTO prepared, StorageMeasurement measurement) {
        var task = owned(id, worker);
        if (task == null || task.stopRequested()) return null;
        var previous = tasks.latestTurn(id);
        if (previous != null && !("SUCCEEDED".equals(previous.state()) || "INTERRUPTED".equals(previous.state()) || "FAILED".equals(previous.state()))) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        var ticket = prepared.ticket();
        if (!id.equals(ticket.taskId()) || !worker.equals(ticket.workerId()) || !task.exerciseId().equals(ticket.exerciseId()) || !task.conversationId().equals(ticket.conversationId())) throw new IllegalArgumentException("Context ticket does not match this worker");
        var head = contexts.head(task.conversationId());
        if ((head == null ? 0 : head.version()) != ticket.version() || tasks.lastSequence(task.conversationId()) != ticket.highWater()
            || !steers.pendingIds(id).equals(ticket.steerIds()) || contexts.active(task.exerciseId())) return null;
        var grant = identity.authorizeMutation(task.sessionHash());
        var target = models.resolveForCall(task.sessionHash(), task.exerciseId());
        if (!target.connection().equals(ticket.target().connection()) || !target.model().equals(ticket.target().model())) return null;
        if (!prepared.plan().safe() || prepared.plan().projection().encodedBytes() > limits.contextBytes()) throw new BusinessException(CopilotErrorCode.COPILOT_CONTEXT_REQUIRED);
        var exercise = workspace.exercise(task.exerciseId());
        Instant notAfter = clock.instant().plus(Duration.ofSeconds(120));
        if (exercise.deadlineAt().isBefore(notAfter)) notAfter = exercise.deadlineAt();
        if (grant.expiresAt().isBefore(notAfter)) notAfter = grant.expiresAt();
        var turn = new CopilotTurnEntity(UUID.randomUUID().toString(), id, task.exerciseId(), previous == null ? 1 : previous.ordinal() + 1,
            "DISPATCHED", target.connection().id(), target.model().id(), Long.parseLong(target.connection().configurationVersion()),
            Long.parseLong(target.model().configurationVersion()), target.connection().protocol().name(), target.model().modelId(),
            target.model().contextWindow(), null, 0, clock.instant(), notAfter, null, null);
        operations.reserve(task.sessionHash(), task.exerciseId(), turn.id(), "COPILOT_MODEL", measurement, MODEL_CREDIT);
        var adjustments = prepared.adjustments();
        if (!adjustments.stream().map(CopilotSteerEntity::id).toList().equals(ticket.steerIds())) throw new IllegalArgumentException("Prepared directions do not match the context ticket");
        for (var steer : adjustments) {
            String message = UUID.randomUUID().toString();
            tasks.message(new CopilotMessageEntity(0, message, id, task.exerciseId(), "USER", steer.messagePayload(), clock.instant()));
            if (steers.stage(steer.id(), message) != 1) throw new IllegalStateException("Steer already included in another request");
        }
        boolean converted = ticket.textHistory();
        var request = prepared.plan().projection().request();
        tasks.turn(turn);
        skills.markExplicitLoaded(task.userId(), task.id(), turn.createdAt());
        tasks.configuration(turn.id(), prepared.configurationPayload());
        tasks.recordPrice(turn.id(), evidence.price(target, turn.createdAt()));
        tasks.historyMode(turn.id(), converted);
        contexts.dispatchContext(turn.id(), prepared.plan().projection().estimatedTokens(), prepared.plan().projection().encodedBytes(),
            ticket.version(), ticket.summaryId(), ticket.throughSequence(), tasks.lastSequence(task.conversationId()), ticket.textHistoryThroughSequence());
        if (!adjustments.isEmpty()) {
            if (steers.applied(id, turn.id(), clock.instant()) != adjustments.size()) throw new IllegalStateException("Steer dispatch lost its ordered instructions");
            for (var steer : adjustments) operations.settle(task.exerciseId(), steer.id(), true);
            tasks.resetProgress(id);
        }
        tasks.renew(id, worker, clock.instant().plus(limits.workerLease()));
        return new CopilotModelDispatchDTO(turn, target, request, converted);
    }

    @Transactional
    public void partial(String id, String worker, String turnId, String text, String thinking) {
        if (text == null || thinking == null || (long) text.getBytes(StandardCharsets.UTF_8).length + thinking.getBytes(StandardCharsets.UTF_8).length > limits.responseBytes()) {
            throw new IllegalArgumentException("Partial response exceeds reserved storage");
        }
        if (owned(id, worker) == null) return;
        var turn = tasks.latestTurn(id);
        if (turn != null && turn.id().equals(turnId)) tasks.partialText(turnId, text, thinking);
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
        if (tasks.response(turnId, tools ? "TOOLS" : "SUCCEEDED", message, input, output, total, input == null || output == null, evidence.usage(usage), clock.instant()) != 1) {
            throw new IllegalStateException("Model response lost its dispatch marker");
        }
        tasks.accounting(turnId, evidence.receipt(evidence.facts(usage), tasks.priceSnapshot(turnId)));
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
        if (task == null || task.stopRequested() || steers.unstaged(id)) return null;
        var turn = tasks.latestTurn(id);
        if (turn == null || !turn.id().equals(turnId) || !"TOOLS".equals(turn.state())) return null;
        var assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(tasks.messageById(turn.assistantMessageId()).payload());
        if (!assistant.toolExecutionRequests().get(turn.toolPosition()).equals(request)) throw new IllegalStateException("Tool no longer matches its durable batch");
        if (tasks.toolAt(turnId, turn.toolPosition()) != null) return null;
        String operation = UUID.randomUUID().toString();
        boolean proposal = "propose_changes".equals(request.name());
        String kind = switch (request.name()) {
            case "propose_changes" -> "COPILOT_PROPOSAL";
            case "run_tests" -> "COPILOT_TEST";
            case "bash" -> "COPILOT_COMMAND";
            default -> "COPILOT_READ";
        };
        operations.reserve(task.sessionHash(), task.exerciseId(), operation, kind, measurement,
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
        parkTool(id, worker, toolId, "WAITING_APPROVAL", "APPROVAL_REQUIRED", true);
    }

    @Transactional
    public void parkTest(String id, String worker, String toolId) {
        parkTool(id, worker, toolId, "WAITING_TEST", "TEST_RUNNING", false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void parkCommandApproval(String id, String worker, String toolId) {
        parkTool(id, worker, toolId, "WAITING_APPROVAL", "COMMAND_APPROVAL_REQUIRED", false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void awaitCommand(String id, String toolId) {
        var task = tasks.find(id);
        var tool = tasks.toolById(toolId);
        if (task == null || task.stopRequested() || task.workerId() != null || !"WAITING_APPROVAL".equals(task.state())
            || tool == null || !id.equals(tool.taskId()) || tasks.waitTool(toolId, "WAITING_COMMAND") != 1
            || tasks.waitFor(id, "WAITING_COMMAND", "COMMAND_RUNNING") != 1) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        }
    }

    private void parkTool(String id, String worker, String toolId, String state, String reason, boolean settlePreparation) {
        var task = owned(id, worker);
        var tool = tasks.toolById(toolId);
        if (task == null || task.stopRequested() || tool == null || !id.equals(tool.taskId()) || !"RUNNING".equals(tool.state())) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        }
        if (tasks.waitTool(toolId, state) != 1 || tasks.park(id, worker, state, reason) != 1) {
            throw new IllegalStateException("Tool did not acquire its durable wait state");
        }
        if (settlePreparation) operations.settle(task.exerciseId(), toolId, true);
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

    /** Called in the transaction that makes the child outcome final. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolveWaitingTool(String id, String toolId, String body) {
        var initial = tasks.find(id);
        if (initial == null) throw new IllegalStateException("Waiting tool task is missing");
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(id);
        var tool = tasks.toolById(toolId);
        var turn = tasks.latestTurn(id);
        if (task.workerId() != null || proposals.pending(id) != null || tests.waiting(id) || commands.pending(id) || tool == null || turn == null || !id.equals(tool.taskId())
            || !turn.id().equals(tool.turnId()) || turn.toolPosition() != tool.position() || !"TOOLS".equals(turn.state())) {
            throw new IllegalStateException("Waiting tool no longer matches its pending batch");
        }
        var assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(tasks.messageById(turn.assistantMessageId()).payload());
        var request = assistant.toolExecutionRequests().get(tool.position());
        if (!request.id().equals(tool.callId()) || !request.name().equals(tool.name()) || !request.arguments().equals(tool.arguments())) {
            throw new IllegalStateException("Waiting tool identity changed");
        }
        if (!recordTool(task, turn, tool, ChatMessageSerializer.messageToJson(ToolExecutionResultMessage.from(request, body)),
            tool.position() + 1 == assistant.toolExecutionRequests().size())) throw new IllegalStateException("Waiting tool result did not advance");
        var exercise = workspace.exercise(task.exerciseId());
        if (exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt())) {
            tasks.haltIdle(id, "EXERCISE_ENDED");
        } else tasks.wake(id);
    }

    private boolean recordTool(CopilotTaskEntity task, CopilotTurnEntity turn, CopilotToolEntity tool, String payload, boolean last) {
        if (payload.getBytes(StandardCharsets.UTF_8).length > CopilotProperties.MAX_TOOL_PAYLOAD_BYTES) throw new IllegalArgumentException("Tool payload exceeds reserved storage");
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
        if (unsettledEffects(task.id())) throw new IllegalStateException("Unconfirmed child operation cannot be interrupted");
        commands.cancelPending(task.id(), reason);
        if ("STEER_SUPERSEDED".equals(reason)) proposals.invalidateSteered(task.id(), clock.instant());
        else proposals.cancelPending(task.id(), reason, clock.instant());
        var assistant = (AiMessage) ChatMessageDeserializer.messageFromJson(tasks.messageById(turn.assistantMessageId()).payload());
        for (int position = turn.toolPosition(); position < assistant.toolExecutionRequests().size(); position++) {
            var request = assistant.toolExecutionRequests().get(position);
            String message = UUID.randomUUID().toString();
            var existing = tasks.toolAt(turn.id(), position);
            String result = "STEER_SUPERSEDED".equals(reason) ? "{\"code\":\"NOT_EXECUTED\",\"reason\":\"STEER_SUPERSEDED\"}"
                : existing == null || "WAITING_APPROVAL".equals(existing.state()) ? "{\"code\":\"NOT_EXECUTED\"}" : "{\"code\":\"INTERRUPTED\"}";
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
        if (steers.unstaged(id)) { tasks.resetProgress(id); return true; }
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
        if (task == null || unsettledEffects(id)) return;
        if ("SUCCEEDED".equals(state) && !task.stopRequested() && steers.pendingCount(id) > 0) {
            var exercise = workspace.exercise(task.exerciseId());
            if (exercise != null && "ACTIVE".equals(exercise.state()) && exercise.deadlineAt() != null && clock.instant().isBefore(exercise.deadlineAt())) {
                tasks.park(id, worker, "QUEUED", "STEER_PENDING");
                return;
            }
            state = "INTERRUPTED"; reason = "EXERCISE_ENDED";
        }
        var turn = tasks.latestTurn(id);
        if (turn != null && "DISPATCHED".equals(turn.state())) {
            tasks.failTurn(turn.id(), "FAILED", reason, clock.instant());
            operations.settle(task.exerciseId(), turn.id(), false);
        }
        if (turn != null && "TOOLS".equals(turn.state())) interruptBatch(task, turn, reason);
        if (!"PAUSED".equals(state)) unappliedSteers(task, reason);
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
        if (unsettledEffects(id)) return;
        var turn = tasks.latestTurn(id);
        if (turn != null && "DISPATCHED".equals(turn.state())) {
            tasks.failTurn(turn.id(), "INTERRUPTED", "MODEL_RESULT_UNKNOWN", clock.instant());
            operations.settle(task.exerciseId(), turn.id(), false);
        }
        if (turn != null && "TOOLS".equals(turn.state())) interruptBatch(task, turn, "SERVICE_INTERRUPTED");
        String reason = turn != null && "DISPATCHED".equals(turn.state()) || contexts.interruptedInline(id) ? "MODEL_RESULT_UNKNOWN" : "SERVICE_INTERRUPTED";
        unappliedSteers(task, reason);
        tasks.finish(id, task.workerId(), task.stopRequested() ? "STOPPED" : "INTERRUPTED", reason, clock.instant());
    }

    @Transactional
    public String interruptionReason(String id, String worker) {
        var task = owned(id, worker);
        if (task == null) return "SERVICE_INTERRUPTED";
        var turn = tasks.latestTurn(id);
        return turn != null && "DISPATCHED".equals(turn.state()) || contexts.interruptedInline(id) ? "MODEL_RESULT_UNKNOWN" : "SERVICE_INTERRUPTED";
    }

    private void recoverIdle(CopilotTaskEntity task) {
        if (!Set.of("QUEUED", "PAUSED", "WAITING_APPROVAL", "WAITING_WRITE", "WAITING_TEST", "WAITING_COMMAND", "STOPPING").contains(task.state())) return;
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
        if (tests.waiting(task.id())) { tests.stop(task.id()); return; }
        if (commands.waiting(task.id())) { commands.stop(task.id()); return; }
        String reason = task.reason() == null ? "USER_STOP" : task.reason();
        var turn = tasks.latestTurn(task.id());
        if (turn != null && "TOOLS".equals(turn.state())) interruptBatch(task, turn, reason);
        unappliedSteers(task, reason);
        tasks.finishIdle(task.id(), "USER_STOP".equals(reason) ? "STOPPED" : "INTERRUPTED", reason, clock.instant());
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
