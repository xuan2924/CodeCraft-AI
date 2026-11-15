package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.workspace.v1.CommandSnapshot;
import ai.codecraft.workspace.v1.ExecutionInventory;
import ai.codecraft.workspace.v1.StopTaskResponse;
import ai.codecraft.workspace.v1.TestEvidencePruningReceipt;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandTaskCoordinatorTest {
    private static final String HOST = "A".repeat(26);
    private final CommandTaskStore store = mock(CommandTaskStore.class);
    private final SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
    private final CommandTaskCoordinator coordinator = new CommandTaskCoordinator(store, sandbox);

    @Test void lostStartResponseIsQueriedThenFencedRatherThanReplayed() {
        var accepted = task(false);
        var dispatched = task(true);
        when(sandbox.inventory("", "")).thenReturn(ExecutionInventory.newBuilder().setHostEpoch(HOST).build());
        when(store.claim("task", HOST)).thenReturn(true, false);
        when(store.find("task")).thenReturn(dispatched);
        when(sandbox.startCommand(dispatched)).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        when(sandbox.stopCommand(dispatched)).thenReturn(cancelled());
        assertThatThrownBy(() -> coordinator.observe(accepted)).isInstanceOf(StatusRuntimeException.class);
        coordinator.observe(dispatched);
        verify(sandbox, times(1)).startCommand(dispatched);
        verify(sandbox).command(dispatched);
        verify(store).cancelledBeforeRegistration("task");
    }

    @Test void existingExecutionIsObservedWithoutStartingOrStoppingIt() {
        var task = task(true);
        var snapshot = CommandSnapshot.getDefaultInstance();
        when(store.find("task")).thenReturn(task);
        when(sandbox.command(task)).thenReturn(snapshot);
        coordinator.observe(task);
        verify(store).observe("task", snapshot, null);
        verify(sandbox).reconcileCommand(task);
        verify(sandbox, never()).startCommand(any());
        verify(sandbox, never()).stopCommand(any());
    }

    @Test void withdrawnIdentityCommitsSafetyStopAfterFailedAdmissionTransaction() {
        var task = task(true);
        when(store.claim("task", HOST)).thenThrow(new IdentityFailure(IdentityErrorCode.IDENTITY_UNAVAILABLE));
        when(store.find("task")).thenReturn(task);
        when(sandbox.stopCommand(task)).thenReturn(cancelled());
        coordinator.observe(task);
        verify(store).requestSystemStop("task");
        verify(store).cancelledBeforeRegistration("task");
        verify(sandbox, never()).startCommand(any());
    }

    @Test void unknownPruningOnlyRetriesItsExistingIdentity() {
        var task = task(true);
        when(store.evictionCandidates()).thenReturn(List.of(task));
        when(store.beginPruning("task")).thenReturn(true);
        when(sandbox.pruneCommand(task)).thenThrow(Status.UNAVAILABLE.asRuntimeException())
            .thenReturn(TestEvidencePruningReceipt.newBuilder().setWorkspaceInstanceId("workspace").setTaskId("task").setExecutionInstanceId("execution").setComplete(true).build());
        coordinator.prune();
        verify(store, never()).pruned(any());
        coordinator.prune();
        verify(store).pruned("task");
        verify(sandbox, times(2)).pruneCommand(task);
        verify(sandbox, never()).startCommand(any());
    }

    private static StopTaskResponse cancelled() { return StopTaskResponse.newBuilder().setHostEpoch(HOST).setCancelledBeforeRegistration(true).build(); }
    private static CommandTaskEntity task(boolean dispatched) {
        return new CommandTaskEntity("task", "exercise", "owner", "workspace", "execution", "session", 1, "request", "fingerprint",
            "exit 7", ".", 1, 2, Instant.parse("2030-01-01T00:00:00Z"), dispatched ? HOST : null, dispatched ? "STARTING" : "ACCEPTED",
            dispatched, false, false, "PENDING", null, 0, false, false, Instant.EPOCH, null);
    }
}
