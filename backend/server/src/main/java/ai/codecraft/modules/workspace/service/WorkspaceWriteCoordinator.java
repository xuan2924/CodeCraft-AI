package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceMutationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceTransitionEntity;
import ai.codecraft.workspace.v1.ApplyBatchRequest;
import ai.codecraft.workspace.v1.GateResponse;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Recoverable RPC coordination, with no database transaction spanning a network call. */
@Service
public class WorkspaceWriteCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWriteCoordinator.class);
    private final WorkspaceCoordinationStore store;
    private final SandboxWorkspaceGateway sandbox;
    private final WorkspaceBatchCodec codec;

    public WorkspaceWriteCoordinator(WorkspaceCoordinationStore store, SandboxWorkspaceGateway sandbox, WorkspaceBatchCodec codec) {
        this.store = store;
        this.sandbox = sandbox;
        this.codec = codec;
    }

    @Scheduled(fixedDelay = 1000)
    public void recover() {
        for (WorkspaceTransitionEntity transition : store.pendingTransitions()) tryDrive(transition.id());
        for (WorkspaceOperationEntity operation : store.pendingMutations()) tryMutation(operation);
    }

    public void tryDrive(String id) {
        try { drive(id); }
        catch (RuntimeException error) { LOG.warn("Workspace transition remains unresolved ({})", error.getClass().getSimpleName()); }
    }

    public void drive(String id) {
        WorkspaceTransitionEntity transition = store.transition(id);
        if (transition == null || transition.phase().equals("SUCCEEDED")) return;
        store.attempted(id);
        if (transition.kind().equals("REVOKE")) {
            WorkspaceControlEntity control = store.control(transition.exerciseId());
            if (control.transitionId() != null && !id.equals(control.transitionId())) drive(control.transitionId());
            if (!store.activateFence(id)) throw new IllegalStateException("Fence waiting for previous transition");
        }
        ExerciseEntity exercise = store.exercise(transition.exerciseId());
        String instance = exercise.workspaceInstanceId();
        if (transition.expectedEpoch() == null) {
            GateResponse gate = sandbox.gate(instance);
            if (!gate.getWorkspaceInstanceId().equals(instance) || gate.getEpoch() < 0
                || !(gate.getState().equals("OPEN") || gate.getState().equals("CLOSED"))) throw new IllegalStateException("Invalid gate authority");
            store.expectation(id, gate.getEpoch());
            transition = store.transition(id);
        }
        if (transition.phase().equals("SUCCEEDED")) return;
        if (transition.expectedEpoch() == null) throw new IllegalStateException("Transition is not active");
        long epoch = transition.expectedEpoch();
        if (transition.phase().equals("CLOSING")) {
            checkGate(sandbox.closeGate(instance, id, epoch), instance, id, epoch + 1, "CLOSED");
            for (WorkspaceOperationEntity operation : store.unresolved(exercise.id())) {
                WorkspaceMutationEntity mutation = store.mutation(operation.id());
                if (mutation.gateEpoch() > epoch) throw new IllegalStateException("Fence does not cover mutation");
                MutationReceipt receipt = sandbox.mutation(instance, operation.id());
                if (receipt == null) store.result(operation, null, null, "WRITE_NOT_COMMITTED");
                else confirm(operation, mutation, receipt);
            }
            store.closed(id);
            transition = store.transition(id);
        }
        if (transition.phase().equals("OPENING")) {
            checkGate(sandbox.openGate(instance, id, epoch + 1), instance, id, epoch + 2, "OPEN");
            store.opened(id);
        }
    }

    public void tryMutation(WorkspaceOperationEntity operation) {
        try { mutate(operation); }
        catch (RuntimeException error) {
            store.unknown(operation.id());
            LOG.warn("Workspace mutation requires reconciliation ({})", error.getClass().getSimpleName());
        }
    }

    private void mutate(WorkspaceOperationEntity operation) {
        WorkspaceMutationEntity claimed = store.claim(operation);
        if (claimed != null) {
            try {
                confirm(operation, claimed, sandbox.apply(codec.request(operation, claimed)));
                return;
            } catch (StatusRuntimeException error) {
                String failure = switch (error.getStatus().getCode()) {
                    case ABORTED -> "REVISION_CONFLICT";
                    case INVALID_ARGUMENT -> "INVALID_REQUEST";
                    case PERMISSION_DENIED -> "PATH_FORBIDDEN";
                    case RESOURCE_EXHAUSTED -> "WORKSPACE_CAPACITY";
                    case ALREADY_EXISTS -> "FILE_EXISTS";
                    case NOT_FOUND -> "FILE_NOT_FOUND";
                    default -> null;
                };
                if (failure != null) { store.result(operation, null, null, failure); return; }
                store.unknown(operation.id());
            }
        }
        // Even when a worker died before sending, dispatched is never reset.
        // Missing receipts require a durable fence, not a second Apply attempt.
        if (store.unresolved(operation.exerciseId()).stream().noneMatch(item -> item.id().equals(operation.id()))) return;
        WorkspaceMutationEntity mutation = store.mutation(operation.id());
        MutationReceipt receipt = sandbox.mutation(operation.workspaceInstanceId(), operation.id());
        if (receipt != null) confirm(operation, mutation, receipt);
        else drive(store.reconcile(operation.exerciseId()));
    }

    private void confirm(WorkspaceOperationEntity operation, WorkspaceMutationEntity mutation, MutationReceipt receipt) {
        ApplyBatchRequest input = codec.request(operation, mutation);
        long expected = input.getExpectedRevision();
        if (!receipt.getWorkspaceInstanceId().equals(operation.workspaceInstanceId()) || !receipt.getRequestId().equals(operation.id())
            || !receipt.getOperationId().equals(operation.id()) || !receipt.getFingerprint().matches("[0-9a-f]{64}")
            || receipt.getRevision() < 0 || (receipt.getChanged() && expected == Long.MAX_VALUE)
            || receipt.getRevision() != expected + (receipt.getChanged() ? 1 : 0)) throw new IllegalStateException("Invalid mutation receipt");
        WorkspaceSnapshot snapshot = sandbox.query(operation.workspaceInstanceId());
        if (snapshot == null || !snapshot.getPrepared() || snapshot.getCancelled()
            || !snapshot.getWorkspaceInstanceId().equals(operation.workspaceInstanceId()) || snapshot.getRevision() < receipt.getRevision()) {
            throw new IllegalStateException("Mutation tree cannot be confirmed");
        }
        store.result(operation, receipt, snapshot, null);
    }

    private static void checkGate(GateResponse gate, String instance, String transition, long epoch, String state) {
        if (!gate.getWorkspaceInstanceId().equals(instance) || !gate.getTransitionId().equals(transition)
            || gate.getEpoch() != epoch || !gate.getState().equals(state)) throw new IllegalStateException("Invalid transition receipt");
    }
}
