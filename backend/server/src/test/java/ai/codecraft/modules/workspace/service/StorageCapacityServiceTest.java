package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.StorageReconciliationPlan;
import ai.codecraft.modules.workspace.model.dto.StorageScope;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import io.grpc.Status;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StorageCapacityServiceTest {
    @Test
    void quotaDenialCollectsAndRemeasuresOnceBeforeRejectingConsumption() {
        var fixture = new Fixture();
        var attempts = new AtomicInteger();
        assertEquals("accepted", fixture.service.admit("session", "exercise", measured -> {
            if (attempts.getAndIncrement() == 0) throw new BusinessException(WorkspaceErrorCode.STORAGE_BUDGET_EXCEEDED);
            return "accepted";
        }));
        verify(fixture.maintenance).collectForAdmission("exercise", Set.of());
        assertEquals(2, attempts.get());
        verify(fixture.sandbox, times(2)).measureStorage(fixture.scope);
    }

    @Test
    void heavyAdmissionAlwaysCalibratesEvenWithAHealthyCache() {
        var fixture = new Fixture();
        when(fixture.store.cachedMeasurement("session", "exercise")).thenReturn(new StorageMeasurement("exercise", "A".repeat(26), 1, 0, true));
        assertEquals(false, fixture.service.admitHeavy("session", "exercise", StorageMeasurement::cached));
        verify(fixture.sandbox).measureStorage(fixture.scope);
    }

    @Test
    void unavailableMeasurementCannotReachAdmissionAndDoesNotPretendToBeZero() {
        var fixture = new Fixture();
        when(fixture.sandbox.measureStorage(fixture.scope)).thenThrow(Status.UNAVAILABLE.asRuntimeException());
        var attempts = new AtomicInteger();
        var failure = assertThrows(BusinessException.class, () -> fixture.service.admit("session", "exercise", measured -> attempts.incrementAndGet()));
        assertEquals("STORAGE_METER_UNAVAILABLE", failure.code());
        assertEquals(0, attempts.get());
        verifyNoInteractions(fixture.maintenance);
    }

    @Test
    void unrelatedBusinessRejectionIsNotRetriedOrConvertedToCapacityFailure() {
        var fixture = new Fixture();
        var attempts = new AtomicInteger();
        var failure = assertThrows(BusinessException.class, () -> fixture.service.admit("session", "exercise", measured -> {
            attempts.incrementAndGet(); throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
        }));
        assertEquals("REVISION_CONFLICT", failure.code());
        assertEquals(1, attempts.get());
        verifyNoInteractions(fixture.maintenance);
    }

    @Test
    void restoreSourceIsProtectedDuringItsOwnCapacityRetry() {
        var fixture = new Fixture();
        var attempts = new AtomicInteger();
        assertEquals("accepted", fixture.service.admit("session", "exercise", Set.of("selected-change"), measured -> {
            if (attempts.getAndIncrement() == 0) throw new BusinessException(WorkspaceErrorCode.STORAGE_BUDGET_EXCEEDED);
            return "accepted";
        }));
        verify(fixture.maintenance).collectForAdmission("exercise", Set.of("selected-change"));
        assertEquals(2, attempts.get());
    }

    private static final class Fixture {
        final StorageCapacityStore store = mock(StorageCapacityStore.class);
        final SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
        final WorkspaceMaintenanceCoordinator maintenance = mock(WorkspaceMaintenanceCoordinator.class);
        final StorageScope scope = new StorageScope("exercise", List.of("workspace"), List.of());
        final StorageCapacityService service = new StorageCapacityService(store, sandbox, maintenance);
        Fixture() {
            when(store.plan("session", "exercise")).thenReturn(new StorageReconciliationPlan(scope, List.of()));
            when(sandbox.measureStorage(scope)).thenReturn(new StorageMeasurement("exercise", "A".repeat(26), 1, 0));
        }
    }
}
