package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Restartable finalization outbox. Business completion never asserts physical cleanup. */
@Service
public class FinalizationCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(FinalizationCoordinator.class);
    private final FinalizationStore store;
    private final WorkspaceCoordinationStore coordination;
    private final WorkspaceWriteCoordinator fences;
    private final TestTaskCoordinator tests;
    private final SandboxWorkspaceGateway sandbox;
    private final StorageCapacityService capacity;
    private final ai.codecraft.modules.workspace.stream.TerminalConnections terminals;

    public FinalizationCoordinator(FinalizationStore store, WorkspaceCoordinationStore coordination,
        WorkspaceWriteCoordinator fences, TestTaskCoordinator tests, SandboxWorkspaceGateway sandbox, StorageCapacityService capacity, ai.codecraft.modules.workspace.stream.TerminalConnections terminals) {
        this.store = store; this.coordination = coordination; this.fences = fences; this.tests = tests; this.sandbox = sandbox; this.capacity = capacity; this.terminals = terminals;
    }

    @Scheduled(fixedDelay = 1000)
    public void recover() {
        for (String exercise : store.expired()) {
            try { store.expire(exercise); }
            catch (RuntimeException error) { LOG.warn("Exercise expiry remains pending ({})", error.getClass().getSimpleName()); }
        }
        for (FinalizationEntity value : store.pending()) tryDrive(value.exerciseId());
    }

    public void tryDrive(String exerciseId) {
        try { drive(exerciseId); }
        catch (RuntimeException error) { LOG.warn("Exercise {} finalization requires reconciliation ({})", exerciseId, error.getClass().getSimpleName()); }
    }

    public void drive(String exerciseId) {
        store.attempted(exerciseId);
        FinalizationEntity value = store.find(exerciseId);
        if (value == null) return;
        terminals.closeExercise(exerciseId);
        if (value.frozenEpoch() == null) {
            String fence = store.ensureFence(exerciseId);
            fences.drive(fence);
            if (!fence.equals(value.id()) || !coordination.transition(fence).phase().equals("SUCCEEDED")) return;
            // Independent test copies do not block freezing. Failed cancellation
            // remains in the finalization outbox and consumes the final budget.
            cancelOutstanding(value);
            var control = coordination.control(exerciseId);
            var exercise = coordination.exercise(exerciseId);
            store.frozen(exerciseId, sandbox.freeze(exercise.workspaceInstanceId(), value.id(), control.gateEpoch()));
        }
        TestTaskEntity allocated;
        try { allocated = store.advance(exerciseId); }
        catch (BusinessException denied) {
            if (denied.errorCode() != WorkspaceErrorCode.STORAGE_BUDGET_EXCEEDED && denied.errorCode() != WorkspaceErrorCode.STORAGE_METER_UNAVAILABLE) throw denied;
            capacity.reconcile(exerciseId);
            allocated = store.advance(exerciseId);
        }
        value = store.find(exerciseId);
        boolean cancelled = cancelOutstanding(value);
        if (value.endedAt() != null && cancelled) store.cancelled(exerciseId);
        if (allocated != null && allocated.dispatched()) tests.observe(allocated);
    }

    private boolean cancelOutstanding(FinalizationEntity value) {
        boolean confirmed = true;
        for (TestTaskEntity task : store.cancellationTargets(value.exerciseId())) {
            try { tests.stopSystem(task); }
            catch (RuntimeException error) {
                confirmed = false;
                LOG.warn("Lifecycle cancellation {} remains pending ({})", task.id(), error.getClass().getSimpleName());
            }
        }
        return confirmed;
    }
}
