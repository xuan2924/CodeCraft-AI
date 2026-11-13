package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.model.dto.TestHistoryRecord;
import java.util.ArrayList;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.repository.DeletionRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceMaintenanceRepository;
import ai.codecraft.workspace.v1.WorkspaceCollectionReceipt;
import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceMaintenanceTest {
    @Test
    void collectionUsesConsistentReferencesAndRetriesStaleSnapshotWithoutDroppingPins() {
        var repository = mock(WorkspaceMaintenanceRepository.class);
        var exercises = mock(ExerciseRepository.class);
        var identity = mock(IdentityTransactionService.class);
        var deletions = mock(DeletionRepository.class);
        var sandbox = mock(SandboxWorkspaceGateway.class);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var store = new WorkspaceMaintenanceStore(repository, exercises, deletions, identity, clock, limits());
        var coordinator = new WorkspaceMaintenanceCoordinator(store, sandbox);
        when(exercises.find("exercise")).thenReturn(exercise(5));
        when(repository.referencedRevisions("exercise")).thenReturn(List.of(2L, 3L, 5L));
        when(sandbox.collectWorkspace("workspace", 5, List.of(2L, 3L, 5L))).thenThrow(Status.ABORTED.asRuntimeException());
        coordinator.collect("exercise");
        verify(repository).checked("exercise", clock.instant());
        when(exercises.find("exercise")).thenReturn(exercise(6));
        when(repository.referencedRevisions("exercise")).thenReturn(List.of(2L, 3L, 5L, 6L));
        when(sandbox.collectWorkspace("workspace", 6, List.of(2L, 3L, 5L, 6L))).thenReturn(
            WorkspaceCollectionReceipt.newBuilder().setWorkspaceInstanceId("workspace").setRevision(6).build());
        coordinator.collect("exercise");
        verify(sandbox).collectWorkspace("workspace", 6, List.of(2L, 3L, 5L, 6L));
        assertEquals(List.of(2L, 3L, 5L, 6L), store.plan("exercise").retainedRevisions());
    }

    @Test
    void deletionOrInvalidReferenceNeverFallsBackToUnpinnedCollection() {
        var repository = mock(WorkspaceMaintenanceRepository.class);
        var exercises = mock(ExerciseRepository.class);
        var sandbox = mock(SandboxWorkspaceGateway.class);
        var store = new WorkspaceMaintenanceStore(repository, exercises, mock(DeletionRepository.class),
            mock(IdentityTransactionService.class), Clock.systemUTC(), limits());
        when(exercises.find("exercise")).thenReturn(exercise(5));
        when(exercises.deleted("exercise")).thenReturn(true);
        assertNull(store.plan("exercise"));
        when(exercises.deleted("exercise")).thenReturn(false);
        when(repository.referencedRevisions("exercise")).thenReturn(List.of(6L));
        new WorkspaceMaintenanceCoordinator(store, sandbox).collect("exercise");
        verify(sandbox, never()).collectWorkspace(anyString(), anyLong(), anyList());
    }

    @Test
    void historyEvictsOnlyOlderCompletedOrdinaryRecordsAndResumesDurableSelections() {
        var repository = mock(WorkspaceMaintenanceRepository.class);
        var exercises = mock(ExerciseRepository.class);
        var store = new WorkspaceMaintenanceStore(repository, exercises, mock(DeletionRepository.class),
            mock(IdentityTransactionService.class), Clock.systemUTC(), limits());
        when(exercises.find("exercise")).thenReturn(exercise(30));
        var records = new ArrayList<TestHistoryRecord>();
        records.add(history("final", true, true, "SUCCEEDED", false, false));
        records.add(history("unknown", false, false, "UNKNOWN", false, false));
        records.add(history("recovery", false, true, "SUCCEEDED", false, true));
        for (int i = 24; i >= 0; i--) records.add(history(Integer.toString(i), false, true, "SUCCEEDED", false, false));
        when(repository.historyInventory("exercise")).thenReturn(records);
        when(repository.markPruningBatch(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
        assertEquals(List.of("4", "3", "2", "1", "0"), store.pruningPlan("exercise").stream().map(TestHistoryRecord::taskId).toList());
        verify(repository).markPruningBatch(List.of("4", "3", "2", "1", "0"));
        records.clear();
        records.add(history("previous-attempt", false, true, "NOT_CREATED", true, false));
        records.add(history("latest", false, true, "NOT_CREATED", false, false));
        assertEquals(List.of("previous-attempt"), store.pruningPlan("exercise").stream().map(TestHistoryRecord::taskId).toList());
        when(exercises.deleted("exercise")).thenReturn(true);
        assertEquals(List.of(), store.pruningPlan("exercise"));
    }

    @Test
    void newlyStaleWriteCannotCreateAReferenceToAnAlreadyCollectableOldVersion() {
        var identity = mock(IdentityTransactionService.class);
        var exercises = mock(ExerciseRepository.class);
        var writes = mock(ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository.class);
        var now = Instant.parse("2026-01-01T00:00:00Z");
        when(identity.authorizeMutation("session")).thenReturn(new ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(60)));
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "workspace", "prepare", "ACTIVE", 30L,
            "[]", 1, now, now, now.plusSeconds(3600), null, null, null));
        when(writes.lock("exercise")).thenReturn(new ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity("exercise", 1, 1L, "OPEN",
            ai.codecraft.modules.identity.service.IdentitySecrets.digest("A".repeat(43)), null, null));
        var service = new WorkspaceWriteTransactionService(identity, mock(ai.codecraft.modules.identity.service.IdentitySecrets.class), exercises, writes, Clock.fixed(now, ZoneOffset.UTC));
        var failure = org.junit.jupiter.api.Assertions.assertThrows(ai.codecraft.common.exception.BusinessException.class,
            () -> service.batch("session", "exercise", "new-key", "A".repeat(43), "{\"expectedRevision\":\"2\"}", 2));
        assertEquals("REVISION_CONFLICT", failure.code());
        verify(exercises, never()).insertOperation(org.mockito.ArgumentMatchers.any());
        verify(writes, never()).insertMutation(org.mockito.ArgumentMatchers.any());
    }

    private StorageProperties limits() { return new StorageProperties(512L * 1024 * 1024, 160L * 1024 * 1024, 20); }

    private TestHistoryRecord history(String id, boolean finalAttempt, boolean quiescent, String copy, boolean pruning, boolean recovery) {
        return new TestHistoryRecord(id, "execution-" + id, "workspace", 1, finalAttempt, quiescent, "STOPPED", "SUCCEEDED", copy, pruning, recovery, Instant.EPOCH, Instant.EPOCH);
    }

    private ExerciseEntity exercise(long revision) {
        return new ExerciseEntity("exercise", "owner", "problem", "workspace", "prepare", "ACTIVE", revision,
            "[]", 1, Instant.EPOCH, null, null, null, null, null);
    }
}
