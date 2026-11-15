package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.workspace.v1.CommandChanges;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CommandTaskServiceTest {
    private final CommandTaskStore store = mock(CommandTaskStore.class);
    private final StorageCapacityService capacity = mock(StorageCapacityService.class);
    private final WorkspaceWriteTransactionService writes = mock(WorkspaceWriteTransactionService.class);
    private final WorkspaceWriteCoordinator fences = mock(WorkspaceWriteCoordinator.class);
    private final SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
    private final CommandTaskCoordinator coordinator = mock(CommandTaskCoordinator.class);
    private final CommandTaskService service = new CommandTaskService(store, capacity, writes, fences, sandbox, coordinator);
    private final CommandStartRequest input = new CommandStartRequest("exit 7", ".", "1");

    @Test void exactReceiptBypassesFreshControlCapacityAndCleanupEffects() {
        var task = task();
        when(store.previous("session", "exercise", "key", input)).thenReturn(task);
        when(store.read("session", "exercise", "task")).thenReturn(task);
        service.start("session", "exercise", "key", "stale", input);
        verify(capacity, never()).admit(any(), any(), any());
        verify(writes, never()).ensureOpen(any(), any(), any());
        verify(coordinator, never()).pruneForAdmission(any());
    }

    @Test void confirmedCapacityRejectionAllowsOneBoundedCleanupAndSameIntentReadmission() {
        when(capacity.<CommandTaskEntity>admit(eq("session"), eq("exercise"), any()))
            .thenThrow(new BusinessException(WorkspaceErrorCode.STORAGE_BUDGET_EXCEEDED)).thenReturn(task());
        when(coordinator.pruneForAdmission("exercise")).thenReturn(true);
        when(store.read("session", "exercise", "task")).thenReturn(task());
        service.start("session", "exercise", "key", "control", input);
        verify(coordinator).pruneForAdmission("exercise");
        verify(capacity, times(2)).admit(eq("session"), eq("exercise"), any());
        verify(sandbox, never()).startCommand(any());
    }

    @Test void uncertainInfrastructureFailureDoesNotTriggerHistoryEviction() {
        when(capacity.admit(eq("session"), eq("exercise"), any())).thenThrow(new BusinessException(WorkspaceErrorCode.STORAGE_METER_UNAVAILABLE));
        assertThatThrownBy(() -> service.start("session", "exercise", "key", "control", input)).isInstanceOf(BusinessException.class);
        verify(coordinator, never()).pruneForAdmission(any());
    }

    @Test void translatedSandboxFailureKeepsItsTechnicalCause() {
        var task = task();
        var failure = io.grpc.Status.UNAVAILABLE.withDescription("fixture transport unavailable").asRuntimeException();
        when(store.read("session", "exercise", "task")).thenReturn(task);
        when(sandbox.commandChanges(task)).thenThrow(failure);
        assertThatThrownBy(() -> service.changes("session", "exercise", "task")).isInstanceOf(BusinessException.class).hasCause(failure);
    }

    @Test void sourceDeliveryReauthorizesAfterSandboxRead() {
        var task = task();
        when(store.read("session", "exercise", "task")).thenReturn(task).thenThrow(new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND));
        when(sandbox.commandChanges(task)).thenReturn(CommandChanges.newBuilder().setWorkspaceInstanceId("workspace").setExecutionInstanceId("execution").setBaseRevision(1).build());
        assertThatThrownBy(() -> service.changes("session", "exercise", "task")).isInstanceOf(BusinessException.class);
        verify(sandbox).commandChanges(task);
        verify(store, times(2)).read("session", "exercise", "task");
    }

    private static CommandTaskEntity task() {
        return new CommandTaskEntity("task", "exercise", "owner", "workspace", "execution", "session", 1, "key", "fingerprint",
            "exit 7", ".", 1, 2, Instant.parse("2030-01-01T00:00:00Z"), "A".repeat(26), "EXITED", true, false, true,
            "UNCHANGED", null, 3, false, false, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
    }
}
