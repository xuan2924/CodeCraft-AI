package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.StorageReconciliationPlan;
import ai.codecraft.modules.workspace.model.dto.StorageScope;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.StorageMeterEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.StorageCapacityRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageCapacityStoreTest {
    private static final long MIB = 1024L * 1024;
    private static final String EPOCH = "A".repeat(26);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void serializedAdmissionsCombineBothStoresAndOutstandingGrowthButLeaveFinalCapacity() {
        var fixture = new Fixture();
        long baseline = fixture.limits.maxBytes() - fixture.limits.recoveryBytes() - 48 * MIB;
        fixture.meter = new StorageMeterEntity("exercise", EPOCH, 20, baseline, NOW);
        fixture.service.admit(fixture.operation("first", "FILE_BATCH"), fixture.measurement(20, baseline), 24 * MIB);
        var denied = assertThrows(BusinessException.class, () -> fixture.service.admit(
            fixture.operation("second", "FILE_BATCH"), fixture.measurement(20, baseline), 16 * MIB));
        assertEquals("STORAGE_BUDGET_EXCEEDED", denied.code());
        assertEquals(24 * MIB, fixture.reservations.values().stream().mapToLong(Long::longValue).sum());
        fixture.service.admitFinal(fixture.operation("final", "FINAL_TEST"));
        assertEquals(2, fixture.reservations.size());
    }

    @Test
    void olderSnapshotsCannotLowerKnownConsumptionAndLostMeterIdentityCannotReleaseCredit() {
        var fixture = new Fixture();
        fixture.meter = new StorageMeterEntity("exercise", EPOCH, 20, 300 * MIB, NOW);
        assertThrows(BusinessException.class, () -> fixture.service.admit(fixture.operation("write", "FILE_BATCH"), fixture.measurement(19, 0), 16 * MIB));
        fixture.reservations.put("pending", 24 * MIB);
        var plan = new StorageReconciliationPlan(new StorageScope("exercise", List.of("workspace"), List.of()), List.of("pending"));
        var changedEpoch = new StorageMeasurement("exercise", "B".repeat(26), 21, 0);
        assertEquals("STORAGE_METER_UNAVAILABLE", assertThrows(BusinessException.class, () -> fixture.service.reconcile(plan, changedEpoch)).code());
        assertEquals(24 * MIB, fixture.reservations.get("pending"));
    }

    @Test
    void settlementReleasesOnlyOperationsKnownConvergedBeforeTheMeasurement() {
        var fixture = new Fixture();
        fixture.reservations.put("before", 24 * MIB);
        fixture.reservations.put("later", 24 * MIB);
        when(fixture.capacity.settledOperations("exercise")).thenReturn(List.of("before", "later"));
        var plan = new StorageReconciliationPlan(new StorageScope("exercise", List.of("workspace"), List.of()), List.of("before"));
        var result = fixture.service.reconcile(plan, fixture.measurement(21, 100 * MIB));
        assertEquals(100 * MIB, result.goBytes());
        assertEquals(24 * MIB, result.reservedBytes());
        verify(fixture.capacity).release(List.of("before"));
    }

    @Test
    void fullOrdinaryUsageStillAdmitsThreeFinalAttemptsAndProtectsMeasurementAndRescueMetadata() {
        var fixture = new Fixture();
        fixture.meter = new StorageMeterEntity("exercise", EPOCH, 20, fixture.limits.maxBytes() - fixture.limits.recoveryBytes() - 16 * MIB, NOW);
        for (int attempt = 0; attempt < 3; attempt++) fixture.service.admitFinal(fixture.operation("final-" + attempt, "FINAL_TEST"));
        long committedAndReserved = fixture.meter.bytes() + 16 * MIB + fixture.reservations.values().stream().mapToLong(Long::longValue).sum();
        org.junit.jupiter.api.Assertions.assertTrue(committedAndReserved + 72 * MIB <= fixture.limits.maxBytes());
    }

    @Test
    void zeroUsageBootstrapIsAllowedOnlyForANewBudget() {
        var fixture = new Fixture();
        fixture.meter = new StorageMeterEntity("exercise", null, 0, 0, NOW);
        when(fixture.capacity.initialize("exercise")).thenReturn(1, 0);
        fixture.service.initialize(fixture.operation("prepare", "PREPARE"));
        assertEquals(StorageCapacityStore.PREPARATION_GROWTH, fixture.reservations.get("prepare"));
        assertThrows(IllegalStateException.class, () -> fixture.service.initialize(fixture.operation("another", "PREPARE")));
    }

    private static final class Fixture {
        final StorageCapacityRepository capacity = mock(StorageCapacityRepository.class);
        final Map<String, Long> reservations = new HashMap<>();
        StorageMeterEntity meter = new StorageMeterEntity("exercise", EPOCH, 20, 0, NOW);
        final StorageCapacityStore service;
        final StorageProperties limits = new StorageProperties(512 * MIB, 256 * MIB, 20);

        Fixture() {
            var exercises = mock(ExerciseRepository.class);
            when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "workspace", "prepare", "ACTIVE", 1L,
                "[]", 1, NOW, NOW, NOW.plusSeconds(3600), null, null, null));
            when(capacity.lock("exercise")).thenAnswer(call -> meter);
            when(capacity.instance(anyString(), anyString())).thenReturn(1);
            when(capacity.databaseBytes("exercise")).thenReturn(16 * MIB);
            when(capacity.reservedBytes("exercise")).thenAnswer(call -> reservations.values().stream().mapToLong(Long::longValue).sum());
            doAnswer(call -> { reservations.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(capacity).reserve(anyString(), anyString(), anyLong(), any());
            doAnswer(call -> { meter = new StorageMeterEntity(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), NOW); return null; })
                .when(capacity).observed(anyString(), anyString(), anyLong(), anyLong(), any());
            when(capacity.release(anyList())).thenAnswer(call -> { List<String> ids = call.getArgument(0); ids.forEach(reservations::remove); return ids.size(); });
            service = new StorageCapacityStore(capacity, exercises, mock(WorkspaceWriteRepository.class), mock(IdentityTransactionService.class),
                limits, Clock.fixed(NOW, ZoneOffset.UTC));
        }
        WorkspaceOperationEntity operation(String id, String kind) {
            return new WorkspaceOperationEntity(id, "owner", "exercise", "workspace", kind, "exercise", id, "a".repeat(64), "PENDING", NOW);
        }
        StorageMeasurement measurement(long sequence, long bytes) { return new StorageMeasurement("exercise", EPOCH, sequence, bytes); }
    }
}
