package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.model.dto.StorageBudgetResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.StorageReconciliationPlan;
import ai.codecraft.modules.workspace.model.dto.StorageScope;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.StorageExecutionEntity;
import ai.codecraft.modules.workspace.model.entity.StorageMeterEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.StorageCapacityRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional half of unified admission; no method invokes an external RPC. */
@Service
public class StorageCapacityStore {
    public static final long MIB = 1024L * 1024;
    public static final long PREPARATION_GROWTH = 32 * MIB;
    public static final long ORDINARY_TEST_GROWTH = 96 * MIB;
    public static final long COMMAND_GROWTH = 192 * MIB;
    public static final long FINAL_TEST_GROWTH = 56 * MIB;
    public static final long CONTROL_GROWTH = 64 * 1024;
    public static final long MUTATION_OVERHEAD = 16 * MIB;
    private static final long PROTECTED_RECOVERY = 72 * MIB;
    private final StorageCapacityRepository capacity;
    private final ExerciseRepository exercises;
    private final WorkspaceWriteRepository writes;
    private final IdentityTransactionService identity;
    private final StorageProperties limits;
    private final Clock clock;
    private final ExerciseDataService data;

    public StorageCapacityStore(StorageCapacityRepository capacity, ExerciseRepository exercises,
        WorkspaceWriteRepository writes, IdentityTransactionService identity, StorageProperties limits, Clock clock, ExerciseDataService data) {
        this.capacity = capacity; this.exercises = exercises; this.writes = writes;
        this.identity = identity; this.limits = limits; this.clock = clock; this.data = data;
    }

    /** Credit and the caller's new durable business intent must commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void admit(WorkspaceOperationEntity operation, StorageMeasurement measurement, long growthBytes) {
        var exercise = exercises.find(operation.exerciseId());
        if (exercise == null || !exercise.userId().equals(operation.userId()) || !operation.state().equals("PENDING")) {
            throw new IllegalStateException("Invalid capacity intent ownership");
        }
        if (exercises.deleted(exercise.id())) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        var meter = observe(capacity.lock(exercise.id()), measurement);
        spend(operation, meter, growthBytes);
    }

    /** Cached usage plus unreleased credits is conservative. A denied final
     * allocation is remeasured by the coordinator outside this transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void admitFinal(WorkspaceOperationEntity operation) {
        var exercise = exercises.find(operation.exerciseId());
        if (!operation.kind().equals("FINAL_TEST") || exercise == null || !exercise.userId().equals(operation.userId())) {
            throw new IllegalStateException("Invalid final capacity intent");
        }
        var meter = capacity.lock(operation.exerciseId());
        if (meter == null || meter.meterEpoch() == null || meter.sequence() < 1) unavailable();
        spend(operation, meter, FINAL_TEST_GROWTH);
    }

    /** Fresh, non-reusable exercise/instance; no earlier Go consumption can exist. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(WorkspaceOperationEntity operation) {
        if (!operation.kind().equals("PREPARE") || capacity.initialize(operation.exerciseId()) != 1) {
            throw new IllegalStateException("Storage budget already initialized");
        }
        registerInstance(operation.exerciseId(), operation.workspaceInstanceId());
        var meter = capacity.lock(operation.exerciseId());
        if (meter.meterEpoch() != null || meter.sequence() != 0 || meter.bytes() != 0) throw new IllegalStateException("Budget is not fresh");
        spend(operation, meter, PREPARATION_GROWTH);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registerInstance(String exercise, String instance) {
        if (capacity.instance(exercise, instance) != 1) throw new IllegalStateException("Workspace identity reused");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registerExecution(TestTaskEntity task) {
        registerExecution(new StorageExecutionEntity(task.exerciseId(), task.id(), task.executionInstanceId(), task.workspaceInstanceId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registerExecution(StorageExecutionEntity execution) {
        if (capacity.execution(execution) != 1) {
            throw new IllegalStateException("Execution identity reused");
        }
    }

    @Transactional
    public StorageReconciliationPlan plan(String session, String id) {
        String user = identity.authorizePrivateDelivery(session).userId();
        var exercise = exercises.find(id);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        return planLocked(exercise);
    }

    @Transactional
    public StorageReconciliationPlan plan(String id) {
        var exercise = exercises.find(id);
        if (exercise == null) return null;
        identity.lockResourceOwner(exercise.userId());
        return planLocked(exercise);
    }

    private StorageReconciliationPlan planLocked(ExerciseEntity exercise) {
        writes.lock(exercise.id());
        capacity.initialize(exercise.id());
        capacity.lock(exercise.id());
        capacity.syncInstances(exercise.id());
        capacity.syncExecutions(exercise.id());
        return new StorageReconciliationPlan(new StorageScope(exercise.id(), capacity.instances(exercise.id()), capacity.executions(exercise.id())),
            List.copyOf(capacity.settledOperations(exercise.id())));
    }

    /** Measurement must have been captured AFTER this plan's completed-operation set. */
    @Transactional
    public StorageBudgetResponse reconcile(StorageReconciliationPlan plan, StorageMeasurement measurement) {
        String id = plan.scope().exerciseId();
        var exercise = exercises.find(id);
        if (exercise == null) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        identity.lockResourceOwner(exercise.userId());
        return reconcileLocked(plan, measurement);
    }

    @Transactional
    public StorageBudgetResponse deliver(String session, StorageReconciliationPlan plan, StorageMeasurement measurement) {
        String user = identity.authorizePrivateDelivery(session).userId();
        String id = plan.scope().exerciseId();
        var exercise = exercises.find(id);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        return reconcileLocked(plan, measurement);
    }

    private StorageBudgetResponse reconcileLocked(StorageReconciliationPlan plan, StorageMeasurement measurement) {
        String id = plan.scope().exerciseId();
        writes.lock(id);
        var meter = observe(capacity.lock(id), measurement);
        var converged = new HashSet<>(capacity.settledOperations(id));
        var releasable = plan.completedOperations().stream().filter(converged::contains).distinct().toList();
        for (int offset = 0; offset < releasable.size(); offset += 1000) capacity.release(releasable.subList(offset, Math.min(offset + 1000, releasable.size())));
        return new StorageBudgetResponse(limits.maxBytes(), limits.recoveryBytes(), meter.bytes(), databaseBytes(id), capacity.reservedBytes(id), meter.checkedAt());
    }

    @Transactional(readOnly = true)
    public List<String> pendingExercises() { return capacity.pendingExercises(); }
    @Transactional
    public void attempted(String id) { capacity.attempted(id, clock.instant()); }

    private StorageMeterEntity observe(StorageMeterEntity meter, StorageMeasurement measurement) {
        if (meter == null || measurement == null || !meter.exerciseId().equals(measurement.exerciseId()) || measurement.sequence() < 1 || measurement.bytes() < 0) unavailable();
        if (measurement.meterEpoch() == null || !measurement.meterEpoch().matches("[A-Z2-7]{26}")) unavailable();
        if (meter.meterEpoch() != null && !meter.meterEpoch().equals(measurement.meterEpoch())) unavailable();
        if (measurement.sequence() < meter.sequence()) return meter;
        if (measurement.sequence() == meter.sequence()) {
            if (measurement.bytes() != meter.bytes()) unavailable();
            return meter;
        }
        capacity.observed(meter.exerciseId(), measurement.meterEpoch(), measurement.sequence(), measurement.bytes(), clock.instant());
        return new StorageMeterEntity(meter.exerciseId(), measurement.meterEpoch(), measurement.sequence(), measurement.bytes(), clock.instant());
    }

    private void spend(WorkspaceOperationEntity operation, StorageMeterEntity meter, long growth) {
        if (growth < 4096) throw new IllegalArgumentException("Storage growth must cover admission metadata");
        long ceiling = limits.maxBytes() - (operation.kind().equals("FINAL_TEST") ? PROTECTED_RECOVERY : limits.recoveryBytes());
        try {
            long committed = Math.addExact(meter.bytes(), databaseBytes(operation.exerciseId()));
            long charged = Math.addExact(committed, capacity.reservedBytes(operation.exerciseId()));
            if (Math.addExact(charged, growth) > ceiling) throw new BusinessException(WorkspaceErrorCode.STORAGE_BUDGET_EXCEEDED);
        } catch (ArithmeticException overflow) { throw new BusinessException(WorkspaceErrorCode.STORAGE_BUDGET_EXCEEDED); }
        capacity.reserve(operation.id(), operation.exerciseId(), growth, clock.instant());
    }

    private long databaseBytes(String id) { return Math.addExact(capacity.databaseBytes(id), data.retainedBytes(id)); }

    private static void unavailable() { throw new BusinessException(WorkspaceErrorCode.STORAGE_METER_UNAVAILABLE); }
}
