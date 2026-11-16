package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotFollowupResumeResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotFollowupEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotFollowupRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotFollowupStore {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "STOPPED", "INTERRUPTED");
    private final CopilotFollowupRepository queue;
    private final CopilotTaskRepository tasks;
    private final CopilotTaskStore starts;
    private final CopilotConversationStore conversations;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore workspace;
    private final WorkspaceAiOperationService operations;
    private final Clock clock;
    public CopilotFollowupStore(CopilotFollowupRepository queue, CopilotTaskRepository tasks, CopilotTaskStore starts,
        CopilotConversationStore conversations, IdentityTransactionService identity,
        WorkspaceCoordinationStore workspace, WorkspaceAiOperationService operations, Clock clock) {
        this.queue = queue; this.tasks = tasks; this.starts = starts; this.conversations = conversations; this.identity = identity;
        this.workspace = workspace; this.operations = operations; this.clock = clock;
    }
    @Transactional
    public List<CopilotFollowupResponse> recent(String session, String exercise, String conversation) {
        conversations.resolveMain(session, exercise, conversation);
        return queue.recent(conversation).stream().sorted(Comparator.comparingLong(CopilotFollowupEntity::sequence)).map(this::describe).toList();
    }
    @Transactional
    public CopilotFollowupResponse previous(String session, String exercise, String conversation, String key, CopilotFollowupRequest input) {
        conversations.resolveMain(session, exercise, conversation); validate(key, input);
        var previous = queue.receipt(exercise, key);
        if (previous == null) return null;
        if (!conversation.equals(previous.conversationId()) || !input.text().equals(previous.text())) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
        return describe(previous);
    }
    @Transactional
    public CopilotFollowupResponse accept(String session, String exercise, String conversation, String credential, String key, CopilotFollowupRequest input, StorageMeasurement measurement) {
        var grant = identity.authorizeMutation(session);
        conversations.resolveMain(session, exercise, conversation); validate(key, input);
        var previous = queue.receipt(exercise, key);
        if (previous != null) {
            if (!conversation.equals(previous.conversationId()) || !input.text().equals(previous.text())) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
            return describe(previous);
        }
        workspace.checkControl(exercise, IdentitySecrets.digest(credential));
        var anchor = tasks.latest(conversation);
        if (anchor == null || TERMINAL.contains(anchor.state())) throw new BusinessException(CopilotErrorCode.COPILOT_FOLLOWUP_NOT_ACTIVE);
        if (queue.pendingCount(conversation) >= 32) throw new BusinessException(CopilotErrorCode.COPILOT_FOLLOWUP_LIMIT);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_FOLLOWUP", measurement, 32768);
        queue.insert(new CopilotFollowupEntity(id, 0, exercise, conversation, grant.userId(), session, IdentitySecrets.digest(credential), key,
            input.text(), anchor.id(), null, "WAITING", null, null, clock.instant(), null, 0, queue.dispatchedSequence(conversation)));
        operations.settle(exercise, id, true);
        return describe(queue.find(id));
    }
    @Transactional
    public CopilotFollowupResponse cancel(String session, String exercise, String conversation, String id) {
        identity.authorizeMutation(session); conversations.resolveMain(session, exercise, conversation);
        var item = queue.find(id);
        if (item == null || !conversation.equals(item.conversationId())) throw new BusinessException(CopilotErrorCode.COPILOT_NOT_FOUND);
        if ("DISPATCHED".equals(item.state())) throw new BusinessException(CopilotErrorCode.COPILOT_FOLLOWUP_STARTED);
        queue.cancel(id, "USER_CANCEL", clock.instant());
        return describe(queue.find(id));
    }
    @Transactional
    public CopilotFollowupResumeResponse previousResume(String session, String exercise, String conversation, String key, boolean fee) {
        conversations.resolveMain(session, exercise, conversation); validateKey(key);
        if (!fee) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        String previous = queue.resumeReceipt(exercise, key);
        if (previous == null) return null;
        if (!conversation.equals(previous)) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
        return new CopilotFollowupResumeResponse(conversation, key);
    }
    @Transactional
    public CopilotFollowupResumeResponse resume(String session, String exercise, String conversation, String credential, String key, boolean fee, StorageMeasurement measurement) {
        identity.authorizeMutation(session); conversations.resolveMain(session, exercise, conversation);
        validateKey(key);
        if (!fee) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        String previous = queue.resumeReceipt(exercise, key);
        if (previous != null) {
            if (!conversation.equals(previous)) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
            return new CopilotFollowupResumeResponse(conversation, key);
        }
        workspace.checkControl(exercise, IdentitySecrets.digest(credential));
        var head = queue.head(conversation);
        if (head == null || !"PAUSED".equals(head.state())) throw new BusinessException(CopilotErrorCode.COPILOT_FOLLOWUP_NOT_ACTIVE);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_FOLLOWUP_RESUME", measurement, 4096);
        queue.resume(conversation, session, IdentitySecrets.digest(credential), predecessor(head));
        queue.resumed(id, exercise, conversation, key, clock.instant());
        operations.settle(exercise, id, true);
        return new CopilotFollowupResumeResponse(conversation, key);
    }
    /** Owner locking sorts dispatch, cancellation, lifecycle closure and queue resumption. No external work here. */
    @Transactional
    public CopilotFollowupEntity ready(String id) {
        var item = lock(id);
        return eligible(item) ? item : null;
    }
    @Transactional
    public void dispatch(String id, StorageMeasurement measurement) {
        var item = lock(id);
        if (!eligible(item)) return;
        var task = starts.acceptFollowup(item.sessionHash(), item.exerciseId(), item.controlHash(), "followup:" + item.id(), item.text(), measurement, item.conversationId());
        if (queue.dispatched(id, task.id(), clock.instant()) != 1) throw new IllegalStateException("Follow-up lost its queue admission");
    }
    @Transactional
    public void pause(String id, long generation, String reason) {
        var item = lock(id);
        if (item != null && item.generation() == generation) queue.pause(id, reason);
    }
    private CopilotFollowupEntity lock(String id) {
        var item = queue.find(id);
        if (item == null) return null;
        identity.lockResourceOwner(item.userId());
        return queue.find(id);
    }
    private boolean eligible(CopilotFollowupEntity item) {
        if (item == null || !Set.of("WAITING", "PAUSED").contains(item.state())) return false;
        var exercise = workspace.exercise(item.exerciseId());
        if (exercise != null && (Set.of("PAUSED", "RESUMING").contains(exercise.state())
            || "PAUSING".equals(exercise.state()) && exercise.deadlineAt() != null && clock.instant().isBefore(exercise.deadlineAt()))) {
            queue.pauseExercise(item.exerciseId()); return false;
        }
        if (exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt())) {
            queue.cancelExercise(item.exerciseId(), "EXERCISE_ENDED", clock.instant()); return false;
        }
        if (!"WAITING".equals(item.state()) || !item.id().equals(queue.head(item.conversationId()).id())) return false;
        var predecessor = tasks.find(predecessor(item));
        if (predecessor == null) { queue.pause(item.id(), "PREDECESSOR_UNAVAILABLE"); return false; }
        if (!TERMINAL.contains(predecessor.state()) || tasks.active(item.exerciseId())) return false;
        if (!"SUCCEEDED".equals(predecessor.state()) && !predecessor.id().equals(item.resumeAfter())) {
            queue.pause(item.id(), "PREDECESSOR_" + predecessor.state()); return false;
        }
        return true;
    }
    private String predecessor(CopilotFollowupEntity item) {
        String previous = queue.previousTask(item.conversationId(), item.sequence(), item.anchorSequence());
        return previous == null ? item.anchorTaskId() : previous;
    }
    private void validate(String key, CopilotFollowupRequest input) {
        validateKey(key);
        if (input == null || !input.feeAcknowledged() || input.text() == null || input.text().isBlank()
            || input.text().indexOf('\0') >= 0 || input.text().getBytes(StandardCharsets.UTF_8).length > 4096) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
    }
    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
    }
    private CopilotFollowupResponse describe(CopilotFollowupEntity item) {
        return new CopilotFollowupResponse(item.id(), Long.toString(item.sequence()), item.exerciseId(), item.conversationId(), item.text(), item.state(), item.reason(), item.taskId(), item.createdAt(), item.completedAt());
    }
}
