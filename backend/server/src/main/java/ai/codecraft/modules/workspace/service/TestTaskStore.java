package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StartTestRequest;
import ai.codecraft.modules.workspace.model.dto.TestHistoryRetentionResponse;
import ai.codecraft.modules.workspace.model.dto.TestHistoryResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.config.ExecutionProperties;
import ai.codecraft.modules.workspace.model.dto.TestProblemResponse;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.dto.TestResultEvidenceDTO;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.TestStopEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import ai.codecraft.modules.workspace.model.entity.FinalTaskAuthorityEntity;
import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import ai.codecraft.modules.workspace.model.entity.TaskStreamEntity;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
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
import org.springframework.transaction.annotation.Propagation;

@Service
public class TestTaskStore {
    private final ExecutionProperties executionLimits;
    private static final Set<String> TERMINAL = Set.of("PASSED", "FAILED", "TIMED_OUT", "STOPPED", "ENVIRONMENT_ERROR", "INTERRUPTED");
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ExerciseRepository exercises;
    private final WorkspaceWriteRepository writes;
    private final TestTaskRepository tasks;
    private final ProblemRepository problems;
    private final Clock clock;
    private final ObjectMapper json;
    private final TaskStreamRepository streams;
    private final TaskStreamPublisher publisher;
    private final FinalizationRepository finals;
    private final StorageProperties storageLimits;
    private final WorkspaceMaintenanceStore maintenance;
    private final StorageCapacityStore capacity;

    public TestTaskStore(ExecutionProperties executionLimits, IdentityTransactionService identity, IdentitySecrets secrets, ExerciseRepository exercises,
        WorkspaceWriteRepository writes, TestTaskRepository tasks, ProblemRepository problems, Clock clock, @Qualifier("workspaceJson") ObjectMapper json,
        TaskStreamRepository streams, TaskStreamPublisher publisher, FinalizationRepository finals,
        StorageProperties storageLimits, WorkspaceMaintenanceStore maintenance, StorageCapacityStore capacity) {
        this.executionLimits = executionLimits;
        this.identity = identity; this.secrets = secrets; this.exercises = exercises; this.writes = writes;
        this.tasks = tasks; this.problems = problems; this.clock = clock; this.json = json;
        this.streams = streams; this.publisher = publisher; this.finals = finals; this.storageLimits = storageLimits; this.maintenance = maintenance; this.capacity = capacity;
    }

    @Transactional(readOnly = true)
    public TestTaskEntity previous(String session, String exercise, String key, String credential, StartTestRequest input) {
        String user = identity.authorization(session).userId();
        owned(user, exercise);
        credential(writes.control(exercise), credential);
        String fingerprint = fingerprint(key, input);
        WorkspaceOperationEntity previous = exercises.receipt(user, "START_TEST", exercise, key);
        if (previous == null) return null;
        if (!previous.fingerprint().equals(fingerprint)) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
        if (tasks.retired(previous.id())) throw new BusinessException(WorkspaceErrorCode.TEST_HISTORY_PRUNED);
        return tasks.find(previous.id());
    }

    @Transactional
    public TestTaskEntity accept(String session, String exerciseId, String key, String credential, StartTestRequest input, StorageMeasurement measurement) {
        return accept(session, exerciseId, key, credential, input, measurement, false);
    }

    /** Only the trusted Copilot admission transaction may use this entry; it must persist its parent binding atomically. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TestTaskEntity acceptCopilot(String session, String exerciseId, String key, StartTestRequest input, StorageMeasurement measurement) {
        return accept(session, exerciseId, key, null, input, measurement, true);
    }

    private TestTaskEntity accept(String session, String exerciseId, String key, String credential, StartTestRequest input, StorageMeasurement measurement, boolean background) {
        SessionAuthorizationDTO authorization = identity.authorizeMutation(session);
        if (background && !"USER".equals(authorization.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        ExerciseEntity exercise = owned(authorization.userId(), exerciseId);
        WorkspaceControlEntity control = writes.lock(exerciseId);
        if (!background) credential(control, credential);
        else if (control == null) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
        String fingerprint = fingerprint(key, input);
        WorkspaceOperationEntity previous = exercises.receipt(authorization.userId(), "START_TEST", exerciseId, key);
        if (previous != null) {
            // Copilot reuses its own durable binding/result, never adopts an unrelated manual receipt.
            if (background) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            if (!previous.fingerprint().equals(fingerprint)) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            if (tasks.retired(previous.id())) throw new BusinessException(WorkspaceErrorCode.TEST_HISTORY_PRUNED);
            return tasks.find(previous.id());
        }
        if (!exercise.state().equals("ACTIVE")) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (!clock.instant().isBefore(exercise.deadlineAt())) throw new BusinessException(WorkspaceErrorCode.EXERCISE_EXPIRED);
        if (control.transitionId() != null || control.fenceId() != null) throw new BusinessException(WorkspaceErrorCode.CONTROL_TRANSITION_PENDING);
        if (!control.gateState().equals("OPEN") || control.gateEpoch() == null) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
        long revision = Long.parseLong(input.expectedRevision());
        if (exercise.revision() == null || exercise.revision() != revision) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
        if (!problems.executionAvailable(exercise.problemVersionId())) throw new BusinessException(WorkspaceErrorCode.TEST_TARGET_UNAVAILABLE);
        if ("challenge".equals(input.target()) && (!exercises.challengeEntered(exerciseId) || problems.challenge(exercise.problemVersionId()) == null)) {
            throw new BusinessException(WorkspaceErrorCode.TEST_TARGET_UNAVAILABLE);
        }
        tasks.lockAdmission();
        if (tasks.occupiedExercise(exerciseId) != 0 || tasks.occupied() >= executionLimits.concurrency()) throw new BusinessException(WorkspaceErrorCode.TEST_SLOT_BUSY);
        Instant notAfter = authorization.expiresAt().isBefore(exercise.deadlineAt()) ? authorization.expiresAt() : exercise.deadlineAt();
        String id = UUID.randomUUID().toString();
        var operation = new WorkspaceOperationEntity(id, authorization.userId(), exerciseId, exercise.workspaceInstanceId(),
            "START_TEST", exerciseId, key, fingerprint, "PENDING", clock.instant());
        capacity.admit(operation, measurement, StorageCapacityStore.ORDINARY_TEST_GROWTH);
        exercises.insertOperation(operation);
        TestTaskEntity task = new TestTaskEntity(id, UUID.randomUUID().toString(), exerciseId, exercise.workspaceInstanceId(), revision,
            control.gateEpoch(), notAfter, input.target(), "ACCEPTED", false, false, null, null, 0);
        capacity.registerExecution(task);
        tasks.insert(task);
        streams.create(task.id(), UUID.randomUUID().toString());
        return task;
    }

    /** Internal convergence after parent stop/expiry; cannot start work and does not depend on an old browser credential. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestSystemStop(String exerciseId, String taskId, String key) {
        var exercise = exercises.find(exerciseId);
        if (exercise == null) throw new IllegalStateException("Owned test exercise is missing");
        identity.lockResourceOwner(exercise.userId());
        writes.lock(exerciseId);
        var task = tasks.lock(taskId);
        if (task == null || !exerciseId.equals(task.exerciseId()) || finals.authority(taskId) != null) throw new IllegalStateException("Owned ordinary test identity mismatch");
        if (TERMINAL.contains(task.state()) && task.quiescent()) return;
        var accepted = tasks.acceptedStop(taskId);
        if (accepted != null) { recoverAuthority(accepted, true); return; }
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid internal stop key");
        var previous = exercises.receipt(exercise.userId(), "STOP_TEST", taskId, key);
        if (previous != null) throw new IllegalStateException("Owned test stop receipt is inconsistent");
        String id = UUID.randomUUID().toString();
        exercises.insertOperation(new WorkspaceOperationEntity(id, exercise.userId(), exerciseId, task.workspaceInstanceId(),
            "STOP_TEST", taskId, key, secrets.fingerprint(taskId), "PENDING", clock.instant()));
        tasks.insertStop(new TestStopEntity(id, taskId, task.gateEpoch(), task.notAfter(), "PENDING", true));
    }

    /** Existing execution evidence only; no RPC and no replacement test. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TestResultEvidenceDTO confirmedResult(String exerciseId, String taskId) {
        writes.lock(exerciseId);
        var task = tasks.lock(taskId);
        if (task == null || !exerciseId.equals(task.exerciseId())) throw new IllegalStateException("Owned test identity mismatch");
        if (!task.quiescent() || !TERMINAL.contains(task.state())) return null;
        if (tasks.retired(taskId)) throw new IllegalStateException("Pending parent test evidence was pruned");
        if (task.snapshot() == null) throw new IllegalStateException("Confirmed test has no stored evidence");
        return new TestResultEvidenceDTO(task.stateVersion(), task.state(), task.snapshot());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resultUnchanged(String exerciseId, String taskId, long version) {
        writes.lock(exerciseId);
        var checkpoint = tasks.resultCheckpoint(taskId);
        return checkpoint != null && exerciseId.equals(checkpoint.exerciseId()) && checkpoint.quiescent() && !checkpoint.retired()
            && checkpoint.version() == version && TERMINAL.contains(checkpoint.state());
    }

    @Transactional
    public TestTaskResponse query(String session, String exercise, String task) {
        owned(identity.authorizePrivateDelivery(session).userId(), exercise);
        maintenance.pruningPlan(exercise);
        TestTaskEntity value = tasks.lock(task);
        if (value == null || !value.exerciseId().equals(exercise)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (tasks.retired(task)) throw new BusinessException(WorkspaceErrorCode.TEST_HISTORY_PRUNED);
        TaskStreamEntity stream = streams.find(task);
        return response(value).withStream(stream.streamId(), TaskStreamCursor.encode(stream, stream.sequence()), Long.toString(stream.firstSequence()), Long.toString(stream.sequence()));
    }

    @Transactional(timeout = 3)
    public TestHistoryResponse recent(String session, String exercise) {
        ExerciseEntity owner = owned(identity.authorizePrivateDelivery(session).userId(), exercise);
        maintenance.pruningPlan(exercise);
        TestProblemResponse problem = TestTaskMapper.problem(problems.find(owner.problemVersionId()));
        return new TestHistoryResponse(tasks.recent(exercise).stream().map(task -> response(task, problem)).toList(),
            new TestHistoryRetentionResponse(storageLimits.completedTests(), tasks.retiredCount(exercise), tasks.pruningCount(exercise)));
    }

    @Transactional
    public TestStopEntity acceptRecoveryStop(String session, String exerciseId, String taskId, String key) {
        return acceptStop(session, exerciseId, taskId, key, null, true);
    }

    @Transactional
    public TestStopEntity acceptStop(String session, String exerciseId, String taskId, String key, String credential) {
        return acceptStop(session, exerciseId, taskId, key, credential, false);
    }

    private TestStopEntity acceptStop(String session, String exerciseId, String taskId, String key, String credential, boolean ownerRecovery) {
        SessionAuthorizationDTO authorization = identity.authorizeMutation(session);
        ExerciseEntity exercise = owned(authorization.userId(), exerciseId);
        WorkspaceControlEntity control = writes.lock(exerciseId);
        if (!ownerRecovery) credential(control, credential);
        TestTaskEntity task = tasks.lock(taskId);
        if (task == null || !task.exerciseId().equals(exerciseId)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (tasks.retired(taskId)) throw new BusinessException(WorkspaceErrorCode.TEST_HISTORY_PRUNED);
        if (ownerRecovery && finals.authority(taskId) != null) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        WorkspaceOperationEntity previous = exercises.receipt(authorization.userId(), "STOP_TEST", taskId, key);
        if (previous != null) {
            if (previous.state().equals("REJECTED")) throw new BusinessException(WorkspaceErrorCode.STALE_CONTROL);
            return recoverAuthority(tasks.stop(previous.id()), ownerRecovery);
        }
        if (!exercise.state().equals("ACTIVE")) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (!ownerRecovery) {
            if (!clock.instant().isBefore(exercise.deadlineAt())) throw new BusinessException(WorkspaceErrorCode.EXERCISE_EXPIRED);
            if (control.transitionId() != null || control.fenceId() != null) throw new BusinessException(WorkspaceErrorCode.CONTROL_TRANSITION_PENDING);
            if (!control.gateState().equals("OPEN") || control.gateEpoch() == null) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
        }
        if (TERMINAL.contains(task.state()) && (!ownerRecovery || task.quiescent())) return null;
        TestStopEntity accepted = tasks.acceptedStop(taskId);
        if (accepted != null) return recoverAuthority(accepted, ownerRecovery);
        String id = UUID.randomUUID().toString();
        exercises.insertOperation(new WorkspaceOperationEntity(id, authorization.userId(), exerciseId, task.workspaceInstanceId(),
            "STOP_TEST", taskId, key, secrets.fingerprint(taskId), "PENDING", clock.instant()));
        Instant notAfter = ownerRecovery || authorization.expiresAt().isBefore(exercise.deadlineAt()) ? authorization.expiresAt() : exercise.deadlineAt();
        TestStopEntity stop = new TestStopEntity(id, taskId, ownerRecovery ? task.gateEpoch() : control.gateEpoch(), notAfter, "PENDING", ownerRecovery);
        tasks.insertStop(stop);
        return stop;
    }

    private TestStopEntity recoverAuthority(TestStopEntity stop, boolean ownerRecovery) {
        if (!ownerRecovery || stop.ownerRecovery() || !stop.state().equals("PENDING")) return stop;
        tasks.recoverStopAuthority(stop.id());
        return new TestStopEntity(stop.id(), stop.taskId(), stop.gateEpoch(), stop.notAfter(), stop.state(), true);
    }

    @Transactional(readOnly = true)
    public List<TestStopEntity> pendingStops() { return tasks.pendingStops(); }

    @Transactional
    public void stopObserved(TestStopEntity stop, boolean accepted, boolean beforeRegistration, String cancellationHostEpoch) {
        TestTaskEntity task = tasks.find(stop.taskId());
        if (task == null) return;
        writes.lock(task.exerciseId());
        task = tasks.lock(task.id());
        TestStopEntity currentStop = tasks.stop(stop.id());
        if (!accepted && currentStop != null && currentStop.ownerRecovery()) return;
        if (beforeRegistration) cancelledBeforeRegistration(task, cancellationHostEpoch);
        String state = accepted ? "SUCCEEDED" : "REJECTED";
        tasks.stopState(stop.id(), state);
        exercises.operationState(stop.id(), state);
    }

    @Transactional
    public void stopAttempted(TestStopEntity stop) { tasks.stopState(stop.id(), "PENDING"); }

    @Transactional(readOnly = true)
    public FinalTaskAuthorityEntity finalAuthority(String taskId) { return finals.authority(taskId); }

    @Transactional
    public void systemCancelledBeforeRegistration(String taskId, String cancellationHostEpoch) {
        TestTaskEntity task = tasks.find(taskId);
        if (task == null) return;
        writes.lock(task.exerciseId());
        task = tasks.lock(taskId);
        cancelledBeforeRegistration(task, cancellationHostEpoch);
    }

    private void cancelledBeforeRegistration(TestTaskEntity task, String cancellationHostEpoch) {
        if (task == null || tasks.retired(task.id())) return;
        if (cancellationHostEpoch == null || cancellationHostEpoch.isBlank()) throw new IllegalStateException("Missing cancellation host generation");
        if (task.dispatched() && !cancellationHostEpoch.equals(task.hostEpoch())) {
            if (!TERMINAL.contains(task.state())) store(task, isolated(task));
            return;
        }
        if (!TERMINAL.contains(task.state())) {
            store(task, new TestTaskResponse(task.id(), task.exerciseId(), task.executionInstanceId(), task.workspaceInstanceId(), Long.toString(task.revision()),
                task.target(), "STOPPED", "CANCELLED_BEFORE_REGISTRATION", null, clock.instant(), null, true, "SUCCEEDED", "NOT_CREATED", "0", false, List.of(), provenance(task), null));
        }
    }

    @Transactional(readOnly = true)
    public List<TestTaskEntity> pending() { return tasks.pending(); }

    @Transactional(readOnly = true)
    public boolean ownsCommand(String exercise, String task, String workspace, String execution) {
        return tasks.ownsCommand(exercise, task, workspace, execution);
    }

    @Transactional(readOnly = true)
    public List<TestTaskEntity> inventoryTasks(List<String> executionIds) {
        if (executionIds.size() > 100) throw new IllegalArgumentException("Inventory page too large");
        return executionIds.isEmpty() ? List.of() : tasks.executions(executionIds);
    }

    @Transactional(readOnly = true)
    public TestTaskEntity find(String id) { return tasks.find(id); }

    @Transactional(readOnly = true)
    public ProblemVersionEntity problem(TestTaskEntity task) {
        String version = exercises.find(task.exerciseId()).problemVersionId();
        var problem = "challenge".equals(task.target()) ? problems.challenge(version) : problems.find(version);
        if (problem == null) throw new IllegalStateException("Task publication is unavailable");
        return problem;
    }

    @Transactional
    public boolean claim(TestTaskEntity task, String hostEpoch) {
        WorkspaceControlEntity control = writes.lock(task.exerciseId());
        TestTaskEntity current = tasks.lock(task.id());
        tasks.attempted(task.id());
        if (current == null || current.dispatched() || !current.state().equals("ACCEPTED")) return false;
        // Stop admission and this claim share the workspace lock; stop-first must not dispatch a new Start.
        if (tasks.acceptedStop(task.id()) != null) return false;
        ExerciseEntity exercise = exercises.find(task.exerciseId());
        FinalTaskAuthorityEntity authority = finals.authority(task.id());
        boolean allowed;
        if (authority == null) {
            if (control.transitionId() != null || control.fenceId() != null) return false;
            allowed = exercise.state().equals("ACTIVE") && control.gateState().equals("OPEN");
        } else {
            FinalizationEntity finalization = finals.find(task.exerciseId());
            allowed = exercise.state().equals("FINAL_TESTING") && control.gateState().equals("FROZEN")
                && finalization.endedAt() == null && authority.finalizationId().equals(finalization.id())
                && task.id().equals(finalization.currentTaskId()) && current.notAfter().equals(finalization.deadlineAt());
        }
        if (!allowed || !clock.instant().isBefore(current.notAfter())
            || control.gateEpoch() == null || control.gateEpoch() != current.gateEpoch()) {
            store(current, interrupted(current, "AUTHORITY_EXPIRED_BEFORE_DISPATCH"));
            return false;
        }
        if (hostEpoch == null || !hostEpoch.matches("[A-Z2-7]{26}")) throw new IllegalStateException("Invalid execution host generation");
        return tasks.dispatch(task.id(), hostEpoch) == 1;
    }

    @Transactional
    public void observe(TestTaskEntity expected, TestTaskResponse result, long stateVersion, String goStream, long goSequence) {
        writes.lock(expected.exerciseId());
        TestTaskEntity current = tasks.lock(expected.id());
        if (current == null || exercises.payloadPurged(current.exerciseId()) || tasks.retired(current.id())) return;
        if (!result.taskId().equals(current.id()) || !result.exerciseId().equals(current.exerciseId())
            || !result.executionInstanceId().equals(current.executionInstanceId()) || !result.workspaceInstanceId().equals(current.workspaceInstanceId())
            || !result.revision().equals(Long.toString(current.revision())) || !result.target().equals(current.target())) throw new IllegalStateException("Mismatched execution receipt");
        if (stateVersion < 0 || goSequence < 0 || stateVersion < tasks.goVersion(current.id())) return;
        TestTaskResponse previous = response(current);
        if (TERMINAL.contains(current.state()) && !current.state().equals(result.state())) {
            if (!result.state().equals("INTERRUPTED") || !result.reason().equals("registry_evidence_lost")) return;
            // Java already committed an authoritative outcome before Go lost its
            // primary evidence. Preserve that outcome, but keep cleanup recoverable.
            result = new TestTaskResponse(previous.taskId(), previous.exerciseId(), previous.executionInstanceId(), previous.workspaceInstanceId(),
                previous.revision(), previous.target(), previous.state(), previous.reason(), previous.startedAt(), previous.finishedAt(), previous.exitCode(),
                result.quiescent(), result.cleanup(), result.copyCleanup(), previous.droppedBytes(), true, previous.logs(), previous.problem(), previous.evidence());
        }
        if (current.quiescent() && !result.quiescent()) return;
        if (previous.copyCleanup().equals("SUCCEEDED") && !result.copyCleanup().equals("SUCCEEDED")) return;
        if (previous.cleanup().equals("SUCCEEDED") && !result.cleanup().equals("SUCCEEDED")) return;
        store(current, result);
        tasks.updateGoVersion(current.id(), stateVersion, goStream, goSequence);
    }

    @Transactional
    public void notRegisteredAfterFence(TestTaskEntity task, boolean hostChanged) {
        writes.lock(task.exerciseId());
        TestTaskEntity current = tasks.lock(task.id());
        if (current == null || TERMINAL.contains(current.state())) return;
        TestTaskResponse result = interrupted(current, hostChanged ? "HOST_RESTART_REGISTRATION_UNCONFIRMED" : "START_NOT_REGISTERED");
        if (hostChanged) {
            // Absence after registry loss does not fence an Engine Create already
            // in flight. Keep the slot isolated; inventory may find it later.
            result = isolated(current);
        }
        store(current, result);
    }

    private TestTaskResponse isolated(TestTaskEntity task) {
        TestTaskResponse result = interrupted(task, "HOST_RESTART_REGISTRATION_UNCONFIRMED");
        return new TestTaskResponse(result.taskId(), result.exerciseId(), result.executionInstanceId(), result.workspaceInstanceId(), result.revision(),
            result.target(), result.state(), result.reason(), result.startedAt(), result.finishedAt(), null, false, "FAILED", "UNKNOWN",
            "0", true, List.of(), result.problem(), null);
    }

    private void store(TestTaskEntity task, TestTaskResponse result) {
        if (exercises.payloadPurged(task.exerciseId()) || tasks.retired(task.id())) return;
        publisher.checkpoint(response(task), result);
        try { tasks.observed(task.id(), result.state(), result.quiescent(), json.writeValueAsString(result)); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot persist execution snapshot", error); }
        if (TERMINAL.contains(result.state())) exercises.operationState(task.id(), "SUCCEEDED");
    }

    private TestProblemResponse provenance(TestTaskEntity task) {
        return TestTaskMapper.problem(problem(task));
    }

    private TestTaskResponse response(TestTaskEntity task) { return response(task, null); }

    private TestTaskResponse response(TestTaskEntity task, TestProblemResponse problem) {
        if (task.snapshot() != null) {
            try { return json.readValue(task.snapshot(), TestTaskResponse.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Invalid persisted execution snapshot", error); }
        }
        return new TestTaskResponse(task.id(), task.exerciseId(), task.executionInstanceId(), task.workspaceInstanceId(), Long.toString(task.revision()),
            task.target(), task.state(), "", null, null, null, false, "PENDING", "PENDING", "0", false, List.of(), problem != null ? problem : provenance(task), null);
    }

    private TestTaskResponse interrupted(TestTaskEntity task, String reason) {
        return new TestTaskResponse(task.id(), task.exerciseId(), task.executionInstanceId(), task.workspaceInstanceId(), Long.toString(task.revision()),
            task.target(), "INTERRUPTED", reason, null, clock.instant(), null, true, "SUCCEEDED", task.dispatched() ? "UNKNOWN" : "NOT_CREATED", "0", false, List.of(), provenance(task), null);
    }

    private String fingerprint(String key, StartTestRequest input) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || input == null || !Set.of("base", "challenge").contains(input.target() == null ? "" : input.target())
            || input.expectedRevision() == null || !input.expectedRevision().matches("0|[1-9][0-9]{0,18}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        try { Long.parseLong(input.expectedRevision()); }
        catch (NumberFormatException error) { throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST); }
        return secrets.fingerprint(input.expectedRevision() + "\n" + input.target());
    }

    private ExerciseEntity owned(String user, String id) {
        ExerciseEntity exercise = exercises.find(id);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        return exercise;
    }
    private static void credential(WorkspaceControlEntity control, String credential) {
        String hash = IdentitySecrets.digest(credential);
        if (control == null || hash == null || !hash.equals(control.credentialHash())) throw new BusinessException(WorkspaceErrorCode.STALE_CONTROL);
    }
}
