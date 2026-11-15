package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotContextResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotContextPreparedDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotCompactionEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short owner-serialized admission transactions; authorization requires a row lock even for delivery. */
@Service
public class CopilotCompactionStore {
    private static final long REQUEST_CREDIT = 64 * 1024;
    private final CopilotCompactionRepository contexts;
    private final CopilotTaskRepository tasks;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ExerciseTransactionService exercises;
    private final WorkspaceCoordinationStore workspace;
    private final WorkspaceAiOperationService operations;
    private final Clock clock;
    private final CopilotConversationStore conversations;

    public CopilotCompactionStore(CopilotCompactionRepository contexts, CopilotTaskRepository tasks,
        IdentityTransactionService identity, IdentitySecrets secrets, ExerciseTransactionService exercises,
        WorkspaceCoordinationStore workspace, WorkspaceAiOperationService operations, Clock clock, CopilotConversationStore conversations) {
        this.conversations = conversations;
        this.contexts = contexts; this.tasks = tasks; this.identity = identity; this.secrets = secrets;
        this.exercises = exercises; this.workspace = workspace; this.operations = operations; this.clock = clock;
    }

    @Transactional
    public CopilotContextResponse current(String session, String exercise) {
        return currentInConversation(session, exercise, exercise);
    }

    @Transactional
    public CopilotContextResponse currentInConversation(String session, String exercise, String conversation) {
        owner(session, exercise);
        String scope = conversations.resolve(session, exercise, conversation);
        var head = contexts.head(scope);
        return new CopilotContextResponse(exercise, head == null ? "0" : Long.toString(head.version()),
            head == null ? null : head.summaryId(), head == null ? "0" : Long.toString(head.throughSequence()),
            head == null || head.summaryId() == null ? null : contexts.summary(head.summaryId(), scope), contexts.recent(scope));
    }

    @Transactional
    public CopilotCompactionResponse replay(String session, String exercise, String key, CopilotCompactionRequest request) {
        owner(session, exercise); validate(key, request);
        var previous = receipt(exercise, key, conversations.resolve(session, exercise, request.conversationId()));
        return previous == null ? null : response(previous);
    }

    @Transactional
    public CopilotCompactionResponse accept(String session, String exercise, String credential, String key,
        CopilotCompactionRequest request, StorageMeasurement measurement) {
        var grant = owner(session, exercise);
        validate(key, request);
        String conversation = conversations.resolve(session, exercise, request.conversationId());
        var previous = receipt(exercise, key, conversation);
        if (previous != null) return response(previous);
        identity.authorizeMutation(session);
        workspace.checkControl(exercise, IdentitySecrets.digest(credential));
        var active = exercises.find(session, exercise);
        if (!"ACTIVE".equals(active.state()) || active.deadlineAt() == null || !clock.instant().isBefore(active.deadlineAt())) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        }
        boolean side = conversations.isBtw(conversation);
        if (side ? contexts.activeInConversation(conversation) : contexts.active(exercise)) throw new BusinessException(CopilotErrorCode.COPILOT_COMPACTION_BUSY);
        var parent = side ? tasks.activeInConversation(conversation) : tasks.activeTask(exercise);
        if (parent != null) {
            if (!conversation.equals(parent.conversationId())) throw new BusinessException(CopilotErrorCode.COPILOT_BUSY);
            var original = identity.tryAuthorizeMutation(parent.sessionHash());
            if (parent.stopRequested() || "STOPPING".equals(parent.state()) || original == null
                || !"USER".equals(original.role()) || !grant.userId().equals(original.userId())) {
                throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
            }
        }
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_COMPACTION", measurement, REQUEST_CREDIT);
        contexts.ensureHead(conversation, clock.instant());
        var job = new CopilotCompactionEntity(id, 0, exercise, grant.userId(), session, grant.authVersion(),
            parent == null ? contexts.anchorTask(conversation) : parent.id(), parent == null ? null : parent.id(),
            key, fingerprint(), "MANUAL", "WAITING", false, null, null, null, null, null, null, 0,
            null, null, null, clock.instant(), null, conversation);
        contexts.insert(job);
        return response(contexts.find(id));
    }

    @Transactional
    public String automatic(String taskId, String worker, CopilotContextPreparedDTO prepared, StorageMeasurement measurement) {
        var initial = tasks.find(taskId);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(taskId);
        if (task == null || !worker.equals(task.workerId()) || task.workerNotAfter() == null || !clock.instant().isBefore(task.workerNotAfter())
            || task.stopRequested() || !"RUNNING".equals(task.state())) return null;
        var grant = owner(task.sessionHash(), task.exerciseId());
        identity.authorizeMutation(task.sessionHash());
        var pending = contexts.pendingConversation(task.conversationId());
        if (pending != null) return pending.id();
        var ticket = prepared.ticket();
        var candidate = prepared.plan().compaction();
        if (!taskId.equals(ticket.taskId()) || !worker.equals(ticket.workerId()) || !task.exerciseId().equals(ticket.exerciseId()) || !task.conversationId().equals(ticket.conversationId())) {
            throw new IllegalArgumentException("Automatic summary does not belong to this worker");
        }
        var head = contexts.head(task.conversationId());
        if (candidate == null || !prepared.plan().automaticNeeded() || (head == null ? 0 : head.version()) != ticket.version()
            || tasks.lastSequence(task.conversationId()) != ticket.highWater()
            || contexts.failedSource(task.conversationId(), ticket.version(), candidate.throughSequence())) return null;
        String id = UUID.randomUUID().toString();
        operations.reserve(task.sessionHash(), task.exerciseId(), id, "COPILOT_COMPACTION", measurement, REQUEST_CREDIT);
        contexts.ensureHead(task.conversationId(), clock.instant());
        contexts.insert(new CopilotCompactionEntity(id, 0, task.exerciseId(), grant.userId(), task.sessionHash(), grant.authVersion(),
            taskId, taskId, "auto-" + id, secrets.fingerprint("AUTO\n" + id), "AUTO", "WAITING", false,
            null, null, null, null, null, null, 0, null, null, null, clock.instant(), null, task.conversationId()));
        return id;
    }

    @Transactional
    public CopilotCompactionResponse cancel(String session, String exercise, String id) {
        owner(session, exercise);
        identity.authorizeMutation(session);
        var job = contexts.find(id);
        if (job == null || !exercise.equals(job.exerciseId())) throw new BusinessException(CopilotErrorCode.COPILOT_COMPACTION_NOT_FOUND);
        contexts.stop(id, "USER_STOP");
        if ("WAITING".equals(job.state())) {
            contexts.finish(id, "CANCELLED", null, "USER_STOP", clock.instant());
            operations.settle(exercise, id, false);
        }
        // A dispatched request may already be charged. Its worker must converge before releasing its reservation.
        return response(contexts.find(id));
    }

    private SessionAuthorizationDTO owner(String session, String exercise) {
        var grant = identity.authorizePrivateDelivery(session);
        if (!"USER".equals(grant.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        exercises.find(session, exercise);
        return grant;
    }
    private void validate(String key, CopilotCompactionRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || request == null || !request.feeAcknowledged()) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
    }
    private String fingerprint() { return secrets.fingerprint("COPILOT_COMPACTION\nMANUAL\ntrue"); }
    private CopilotCompactionEntity receipt(String exercise, String key, String conversation) {
        var previous = contexts.receipt(exercise, key);
        if (previous != null && (!previous.conversationId().equals(conversation) || !previous.fingerprint().equals(fingerprint()))) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
        return previous;
    }
    private CopilotCompactionResponse response(CopilotCompactionEntity job) {
        return new CopilotCompactionResponse(job.id(), Long.toString(job.sequence()), job.exerciseId(), job.triggerKind(), job.state(), job.failureCode(),
            job.attemptCount(), job.stopRequested(), job.sourceVersion() == null ? null : Long.toString(job.sourceVersion()),
            job.throughSequence() == null ? null : Long.toString(job.throughSequence()), job.createdAt(), job.completedAt());
    }
}
