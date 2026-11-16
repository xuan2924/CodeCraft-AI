package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ExercisePauseEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceTransitionEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExercisePauseRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pause intent and confirmation are separate transactions; no network calls hold these locks. */
@Service
public class ExercisePauseStore {
    private final ExercisePauseRepository pauses;
    private final ExerciseRepository exercises;
    private final WorkspaceWriteRepository writes;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ExerciseDataService data;
    private final Clock clock;

    public ExercisePauseStore(ExercisePauseRepository pauses, ExerciseRepository exercises, WorkspaceWriteRepository writes,
        IdentityTransactionService identity, IdentitySecrets secrets, ExerciseDataService data, Clock clock) {
        this.pauses = pauses; this.exercises = exercises; this.writes = writes; this.identity = identity;
        this.secrets = secrets; this.data = data; this.clock = clock;
    }

    @Transactional
    public WorkspaceOperationEntity request(String session, String exerciseId, String key, String credential, boolean resume) {
        String user = identity.authorizeMutation(session).userId();
        var exercise = exercises.find(exerciseId);
        if (exercise == null || !user.equals(exercise.userId())) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(exerciseId)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        var control = writes.lock(exerciseId);
        String kind = resume ? "RESUME" : "PAUSE";
        var previous = exercises.receipt(user, kind, exerciseId, key);
        if (previous != null) return previous;
        String hash = IdentitySecrets.digest(credential);
        if (control == null || hash == null || !hash.equals(control.credentialHash())) throw new BusinessException(WorkspaceErrorCode.STALE_CONTROL);
        if (control.transitionId() != null || control.fenceId() != null) throw new BusinessException(WorkspaceErrorCode.CONTROL_TRANSITION_PENDING);
        exercise = exercises.find(exerciseId);
        if (!(resume ? "PAUSED" : "ACTIVE").equals(exercise.state())) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        var now = clock.instant();
        if (!resume && (exercise.deadlineAt() == null || !now.isBefore(exercise.deadlineAt()))) throw new BusinessException(WorkspaceErrorCode.EXERCISE_EXPIRED);
        String id = UUID.randomUUID().toString();
        if (resume) {
            var pause = pauses.find(exerciseId);
            if (pause == null || pause.remainingMillis() == null || pause.remainingMillis() <= 0 || !"CLOSED".equals(control.gateState())) {
                throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
            }
            if (pauses.requestResume(exerciseId) != 1) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
            pauses.resumeOperation(exerciseId, id);
        } else {
            if (pauses.requestPause(exerciseId, now) != 1) throw new BusinessException(WorkspaceErrorCode.EXERCISE_EXPIRED);
            pauses.accept(exerciseId, id);
            // Holding queues here prevents a fast pause/resume from escaping a worker's next poll.
            data.pause(exerciseId);
        }
        var operation = new WorkspaceOperationEntity(id, user, exerciseId, exercise.workspaceInstanceId(), kind, exerciseId, key,
            secrets.fingerprint(kind), "PENDING", now);
        exercises.insertOperation(operation);
        writes.insertTransition(new WorkspaceTransitionEntity(id, exerciseId, kind, null, "CLOSING", hash));
        writes.activate(exerciseId, id);
        return operation;
    }

    @Transactional(readOnly = true)
    public List<String> pending() { return pauses.pending(); }
    @Transactional(readOnly = true)
    public ExercisePauseEntity find(String exercise) { return pauses.find(exercise); }
    @Transactional(readOnly = true)
    public List<TestTaskEntity> tests(String exercise) { return pauses.tests(exercise); }
    @Transactional(readOnly = true)
    public List<CommandTaskEntity> commands(String exercise) { return pauses.commands(exercise); }
    @Transactional
    public void attempted(String exercise) { pauses.attempted(exercise, clock.instant()); }

    @Transactional
    public boolean confirmPause(String id) {
        var exercise = lock(id);
        if (exercise == null || !"PAUSING".equals(exercise.state())) return false;
        var control = writes.control(id);
        var pause = pauses.find(id);
        if (!idleClosed(control) || !writes.unresolved(id).isEmpty() || !pauses.executionsQuiescent(id) || !data.quiescent(id)) return false;
        var now = clock.instant();
        if (exercise.deadlineAt() == null || !now.isBefore(exercise.deadlineAt())) return false;
        long remaining = Duration.between(now, exercise.deadlineAt()).toMillis();
        if (remaining <= 0) return false;
        pauses.remaining(id, remaining, now);
        if (pauses.paused(id) != 1) throw new IllegalStateException("Pause confirmation lost its lifecycle lock");
        exercises.operationState(pause.pauseOperationId(), "SUCCEEDED");
        return true;
    }

    @Transactional
    public void confirmResume(String id) {
        var exercise = lock(id);
        if (exercise == null || !"RESUMING".equals(exercise.state())) return;
        var pause = pauses.find(id);
        var control = writes.control(id);
        var transition = writes.transition(pause.resumeOperationId());
        if (!"SUCCEEDED".equals(transition.phase()) || control.transitionId() != null || control.fenceId() != null) return;
        if (!"OPEN".equals(control.gateState()) || !transition.targetHash().equals(control.credentialHash())
            || transition.expectedEpoch() == null || control.gateEpoch() == null || control.gateEpoch() != transition.expectedEpoch() + 2) {
            // A revocation/reconciliation superseded reopening. Never silently grant fresh answering time.
            if (!"CLOSED".equals(control.gateState())) return;
            pauses.paused(id);
            exercises.operationState(pause.resumeOperationId(), "REJECTED");
            return;
        }
        if (pauses.resumed(id, clock.instant().plusMillis(pause.remainingMillis())) != 1) throw new IllegalStateException("Resume confirmation lost its lifecycle lock");
        exercises.operationState(pause.resumeOperationId(), "SUCCEEDED");
    }

    @Transactional
    public void superseded(String id) {
        var exercise = lock(id);
        if (exercise == null || List.of("PAUSING", "PAUSED", "RESUMING").contains(exercise.state())) return;
        var pause = pauses.find(id);
        if (pause == null) return;
        exercises.operationState(pause.pauseOperationId(), "REJECTED");
        if (pause.resumeOperationId() != null) exercises.operationState(pause.resumeOperationId(), "REJECTED");
    }

    private ExerciseEntity lock(String id) {
        var initial = exercises.find(id);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        writes.lock(id);
        return exercises.find(id);
    }

    private boolean idleClosed(WorkspaceControlEntity control) {
        return control != null && "CLOSED".equals(control.gateState()) && control.transitionId() == null && control.fenceId() == null;
    }
}
