package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.FinalizationProperties;
import ai.codecraft.modules.workspace.config.ExecutionProperties;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import ai.codecraft.modules.workspace.model.entity.FinalTaskAuthorityEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceTransitionEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.workspace.v1.GateResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;

/** Durable lifecycle transactions. No RPC is performed while database locks are held. */
@Service
public class FinalizationStore {
    private SandboxRoutingStore routing;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRouting(SandboxRoutingStore routing) { this.routing = routing; }
    private final ExecutionProperties executionLimits;
    private static final Set<String> VERIFIED = Set.of("PASSED", "FAILED", "TIMED_OUT");
    private static final Set<String> RETRYABLE = Set.of("ENVIRONMENT_ERROR", "INTERRUPTED", "STOPPED");
    private final FinalizationRepository finals;
    private final ExerciseRepository exercises;
    private final WorkspaceWriteRepository writes;
    private final TestTaskRepository tasks;
    private final TaskStreamRepository streams;
    private final ProblemRepository problems;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final Clock clock;
    private final FinalizationProperties properties;
    private final FinalVerificationPolicy policy;
    private final ObjectMapper json;
    private final StorageCapacityStore capacity;

    public FinalizationStore(ExecutionProperties executionLimits, FinalizationRepository finals, ExerciseRepository exercises, WorkspaceWriteRepository writes,
        TestTaskRepository tasks, TaskStreamRepository streams, ProblemRepository problems, IdentityTransactionService identity,
        IdentitySecrets secrets, Clock clock, FinalizationProperties properties, FinalVerificationPolicy policy, @Qualifier("workspaceJson") ObjectMapper json, StorageCapacityStore capacity) {
        this.executionLimits = executionLimits;
        this.finals = finals; this.exercises = exercises; this.writes = writes; this.tasks = tasks; this.streams = streams;
        this.problems = problems; this.identity = identity; this.secrets = secrets; this.clock = clock;
        this.properties = properties; this.policy = policy; this.json = json; this.capacity = capacity;
    }

    @Transactional
    public FinalizationEntity recoveryEnd(String session, String exerciseId, String key) {
        return end(session, exerciseId, key, null, true);
    }

    @Transactional
    public FinalizationEntity end(String session, String exerciseId, String key, String credential) {
        return end(session, exerciseId, key, credential, false);
    }

    private FinalizationEntity end(String session, String exerciseId, String key, String credential, boolean ownerRecovery) {
        String user = identity.authorizeMutation(session).userId();
        ExerciseEntity exercise = exercises.find(exerciseId);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(exerciseId)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        WorkspaceControlEntity control = writes.lock(exerciseId);
        FinalizationEntity existing = finals.find(exerciseId);
        // Ended/closing is an observation, not fresh answering authority.
        if (existing != null) return existing;
        String hash = IdentitySecrets.digest(credential);
        if (!ownerRecovery && (control == null || hash == null || !hash.equals(control.credentialHash()))) throw new BusinessException(WorkspaceErrorCode.STALE_CONTROL);
        String reason = exercise.deadlineAt() != null && !clock.instant().isBefore(exercise.deadlineAt()) ? "DEADLINE_EXPIRED" : "USER_ENDED";
        return begin(exerciseId, reason, key);
    }

    @Transactional
    public void expire(String exerciseId) {
        writes.lock(exerciseId);
        ExerciseEntity exercise = exercises.find(exerciseId);
        if (exercise == null || !Set.of("ACTIVE", "PAUSING").contains(exercise.state()) || exercise.deadlineAt() == null || clock.instant().isBefore(exercise.deadlineAt())) return;
        begin(exerciseId, "DEADLINE_EXPIRED", "deadline");
    }

    private FinalizationEntity begin(String exerciseId, String reason, String key) {
        FinalizationEntity existing = finals.find(exerciseId);
        if (existing != null) return existing;
        ExerciseEntity exercise = exercises.find(exerciseId);
        if (finals.closing(exerciseId, reason) != 1) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        String id = UUID.randomUUID().toString();
        FinalizationEntity accepted = new FinalizationEntity(id, exerciseId, null, null, null, null, properties.maxAttempts(),
            properties.budgetSeconds(), properties.firstRetrySeconds(), properties.secondRetrySeconds(), 0, null, null, "PENDING", null, null, true);
        finals.insert(accepted);
        exercises.insertOperation(new WorkspaceOperationEntity(id, exercise.userId(), exerciseId, exercise.workspaceInstanceId(),
            "END", exerciseId, key, secrets.fingerprint("END"), "PENDING", clock.instant()));
        // Close Java dispatch admission in the same transaction as CLOSING.
        // An existing revocation fence already blocks admission and converges first.
        WorkspaceControlEntity control = writes.lock(exerciseId);
        if (control.fenceId() == null) {
            writes.insertTransition(new WorkspaceTransitionEntity(id, exerciseId, "END", null, "CLOSING", null));
            writes.fence(exerciseId, id);
        }
        return accepted;
    }

    @Transactional
    public String ensureFence(String exerciseId) {
        WorkspaceControlEntity control = writes.lock(exerciseId);
        FinalizationEntity value = finals.find(exerciseId);
        if (control.fenceId() != null) return control.fenceId();
        if (writes.transition(value.id()) == null) writes.insertTransition(new WorkspaceTransitionEntity(value.id(), exerciseId, "END", null, "CLOSING", null));
        writes.fence(exerciseId, value.id());
        return value.id();
    }

    @Transactional(readOnly = true)
    public List<String> expired() { return finals.expired(clock.instant()); }
    @Transactional(readOnly = true)
    public List<FinalizationEntity> pending() { return finals.pending(); }
    @Transactional(readOnly = true)
    public FinalizationEntity find(String exerciseId) { return finals.find(exerciseId); }
    @Transactional
    public List<TestTaskEntity> cancellationTargets(String exerciseId) {
        writes.lock(exerciseId);
        FinalizationEntity current = finals.find(exerciseId);
        return finals.outstanding(exerciseId).stream()
            .filter(task -> current.endedAt() != null || !task.id().equals(current.currentTaskId())).toList();
    }
    @Transactional
    public void attempted(String exerciseId) { finals.attempted(exerciseId, clock.instant()); }

    @Transactional
    public void frozen(String exerciseId, GateResponse receipt) {
        WorkspaceControlEntity control = writes.lock(exerciseId);
        FinalizationEntity value = finals.find(exerciseId);
        ExerciseEntity exercise = exercises.find(exerciseId);
        if (value.frozenEpoch() != null) return;
        if (!exercise.state().equals("CLOSING") || !value.id().equals(control.fenceId()) || !control.gateState().equals("CLOSED")
            || !writes.unresolved(exerciseId).isEmpty() || !receipt.getWorkspaceInstanceId().equals(exercise.workspaceInstanceId())
            || !receipt.getTransitionId().equals(value.id()) || !receipt.getState().equals("FROZEN") || receipt.getFrozenRevision() < 0
            || exercise.revision() == null || receipt.getFrozenRevision() != exercise.revision()
            || control.gateEpoch() == null || receipt.getEpoch() != control.gateEpoch() + 1) throw new IllegalStateException("Invalid freeze receipt");
        Instant now = clock.instant();
        finals.frozen(exerciseId, receipt.getEpoch(), receipt.getFrozenRevision(), now, now.plusSeconds(value.budgetSeconds()));
        writes.gate(exerciseId, receipt.getEpoch(), "FROZEN");
        writes.holder(exerciseId, null);
        finals.testing(exerciseId, receipt.getFrozenRevision());
    }

    /** Returns a newly allocated task, or null while waiting/after conclusion. */
    @Transactional
    public TestTaskEntity advance(String exerciseId, StorageMeasurement measurement) {
        writes.lock(exerciseId);
        FinalizationEntity value = finals.find(exerciseId);
        ExerciseEntity exercise = exercises.find(exerciseId);
        if (value == null || value.endedAt() != null || value.frozenEpoch() == null) return null;
        TestTaskEntity reusable = finals.reusable(exerciseId, exercise.workspaceInstanceId(), value.finalRevision());
        if (reusable != null && validResult(exercise, value, reusable)) { finish(value, reusable.state(), reusable.id()); return null; }
        if (policy.expired(value)) { finish(value, "UNAVAILABLE", null); return null; }
        if (value.currentTaskId() != null) {
            TestTaskEntity current = tasks.find(value.currentTaskId());
            if (VERIFIED.contains(current.state()) && validResult(exercise, value, current)) { finish(value, current.state(), current.id()); return null; }
            if (!RETRYABLE.contains(current.state())) return null;
            if (policy.exhausted(value)) { finish(value, "UNAVAILABLE", null); return null; }
            if (!current.quiescent()) return null;
            TestTaskResponse result = snapshot(current);
            Instant finished = result.finishedAt() != null ? result.finishedAt() : clock.instant();
            finals.retry(exerciseId, current.id(), policy.retryAt(value, finished));
            return null;
        }
        if (policy.exhausted(value)) { finish(value, "UNAVAILABLE", null); return null; }
        if (value.nextAttemptAt() != null && clock.instant().isBefore(value.nextAttemptAt())) return null;
        tasks.lockAdmission();
        if (routing != null) routing.admitExecution(exerciseId);
        if (tasks.occupiedExercise(exerciseId) != 0 || tasks.occupied() >= executionLimits.concurrency()) return null;
        if (measurement == null) throw new StorageCapacityStore.CalibrationRequired();
        String id = UUID.randomUUID().toString();
        if (finals.allocate(exerciseId, id) != 1) return null;
        TestTaskEntity task = new TestTaskEntity(id, UUID.randomUUID().toString(), exerciseId, exercise.workspaceInstanceId(), value.finalRevision(),
            value.frozenEpoch(), value.deadlineAt(), exercises.challengeEntered(exerciseId) ? "challenge" : "base", "ACCEPTED", false, false, null, null, 0);
        var operation = new WorkspaceOperationEntity(id, exercise.userId(), exerciseId, exercise.workspaceInstanceId(),
            "FINAL_TEST", value.id(), id, secrets.fingerprint(value.id() + "\n" + id), "PENDING", clock.instant());
        capacity.admitFinal(operation, measurement);
        capacity.registerExecution(task);
        exercises.insertOperation(operation);
        tasks.insert(task);
        finals.bind(new FinalTaskAuthorityEntity(id, value.id(), value.id(), value.deadlineAt()));
        streams.create(id, UUID.randomUUID().toString());
        return task;
    }

    @Transactional
    public void cancelled(String exerciseId) { writes.lock(exerciseId); finals.cancelled(exerciseId); }

    private void finish(FinalizationEntity value, String verification, String task) {
        finals.finish(value.exerciseId(), clock.instant(), verification, task);
        finals.ended(value.exerciseId());
        exercises.endedAt(value.exerciseId(), clock.instant());
        exercises.operationState(value.id(), "SUCCEEDED");
    }

    private boolean validResult(ExerciseEntity exercise, FinalizationEntity value, TestTaskEntity task) {
        TestTaskResponse result = snapshot(task);
        boolean challenge = exercises.challengeEntered(exercise.id());
        var expected = challenge ? problems.challenge(exercise.problemVersionId()) : problems.find(exercise.problemVersionId());
        return expected != null && task.workspaceInstanceId().equals(exercise.workspaceInstanceId()) && task.revision() == value.finalRevision()
            && task.target().equals(challenge ? "challenge" : "base") && VERIFIED.contains(result.state()) && result.state().equals(task.state())
            && result.problem().equals(TestTaskMapper.problem(expected))
            && (result.state().equals("TIMED_OUT") || result.evidence() != null);
    }

    private TestTaskResponse snapshot(TestTaskEntity task) {
        try { return json.readValue(task.snapshot(), TestTaskResponse.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Invalid final verification evidence", error); }
    }
}
