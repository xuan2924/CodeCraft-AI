package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.StorageMeterEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.StorageCapacityRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StorageCapacityDeliveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delayedMeasurementRechecksSessionAndDeletionBeforePrivateDelivery(boolean deleted) {
        var identity = mock(IdentityTransactionService.class);
        var exercises = mock(ExerciseRepository.class);
        var capacity = mock(StorageCapacityRepository.class);
        var gateway = mock(SandboxWorkspaceGateway.class);
        var completedRpc = new AtomicBoolean();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(identity.authorizePrivateDelivery("session")).thenAnswer(call -> {
            if (!deleted && completedRpc.get()) throw new BusinessException(IdentityErrorCode.UNAUTHENTICATED);
            return new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(60));
        });
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "workspace", "prepare", "ACTIVE", 1L,
            "[]", 1, now, now, now.plusSeconds(2700), null, null, null));
        when(exercises.deleted("exercise")).thenAnswer(call -> deleted && completedRpc.get());
        when(capacity.lock("exercise")).thenReturn(new StorageMeterEntity("exercise", "A".repeat(26), 1, 250L * 1024 * 1024, now));
        when(gateway.measureStorage(any())).thenAnswer(call -> {
            completedRpc.set(true);
            return new StorageMeasurement("exercise", "A".repeat(26), 2, 1024);
        });
        var store = new StorageCapacityStore(capacity, exercises, mock(WorkspaceWriteRepository.class), identity,
            new StorageProperties(536870912, 268435456, 20), Clock.fixed(now, ZoneOffset.UTC), mock(ExerciseDataService.class));
        var service = new StorageCapacityService(store, gateway, mock(WorkspaceMaintenanceCoordinator.class));
        var failure = assertThrows(BusinessException.class, () -> service.usage("session", "exercise"));
        assertEquals(deleted ? "EXERCISE_DELETED" : "UNAUTHENTICATED", failure.code());
    }
}
