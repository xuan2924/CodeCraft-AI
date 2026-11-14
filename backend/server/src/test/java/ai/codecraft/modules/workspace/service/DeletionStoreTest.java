package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.workspace.config.RetentionProperties;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.ExecutionCleanupDTO;
import ai.codecraft.workspace.v1.WorkspaceDeletionReceipt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import ai.codecraft.modules.workspace.model.entity.DeletionEntity;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.repository.DeletionRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import static org.mockito.ArgumentMatchers.anyString;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeletionStoreTest {
    @Test
    void acceptedDeletionRevokesExerciseReadRatherThanBecomingAnAmbiguousNotFound() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ExerciseRepository exercises = mock(ExerciseRepository.class);
        IdentityTransactionService identity = mock(IdentityTransactionService.class);
        when(identity.authorization("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(60)));
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "workspace", "prepare", "ENDED", 7L, "[]", 1, now, now, now, null, null, null));
        when(exercises.deleted("exercise")).thenReturn(true);
        ExerciseTransactionService service = new ExerciseTransactionService(exercises, identity, mock(ProblemTransactionService.class), Clock.fixed(now, ZoneOffset.UTC), mock(IdentitySecrets.class), mock(WorkspaceCoordinationStore.class), new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), mock(StorageCapacityStore.class), mock(ExerciseModelService.class));
        assertThrows(BusinessException.class, () -> service.find("session", "exercise"));
    }

    @Test
    void retriesAndInterventionUseControlledTimeAndUnfinishedProgressNeverExpires() {
        MutableClock clock = new MutableClock();
        DeletionRepository deletions = mock(DeletionRepository.class);
        ExerciseRepository exercises = mock(ExerciseRepository.class);
        IdentityTransactionService identity = mock(IdentityTransactionService.class);
        when(identity.authorization("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, clock.instant().plusSeconds(60)));
        DeletionEntity[] value = {new DeletionEntity("exercise", "owner", "workspace", "PENDING", clock.instant(), null, 0, clock.instant(), null, null)};
        when(deletions.find("exercise")).thenAnswer(call -> value[0]);
        when(deletions.due(any())).thenAnswer(call -> !clock.instant().isBefore(value[0].nextAttemptAt()) ? List.of(value[0]) : List.of());
        doAnswer(call -> {
            DeletionEntity old = value[0];
            value[0] = new DeletionEntity(old.exerciseId(), old.userId(), old.workspaceInstanceId(), "FAILED", old.requestedAt(), null,
                old.attempts() + 1, call.getArgument(3), old.firstFailureAt() == null ? call.getArgument(2) : old.firstFailureAt(), call.getArgument(4));
            return null;
        }).when(deletions).failed(anyString(), anyString(), any(), any(), anyString());
        DeletionStore service = new DeletionStore(deletions, exercises, identity, clock, new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), new ExerciseDataService(List.of()));
        for (long interval : new long[]{60, 300, 1800, 3600, 3600}) {
            Instant failure = clock.instant();
            service.failed(value[0], "claim", "STORAGE_CLEANUP_FAILED");
            assertEquals(failure.plusSeconds(interval), service.status("session", "exercise").nextAttemptAt());
            clock.now = failure.plusSeconds(interval - 1);
            assertEquals(List.of(), service.due());
            clock.now = failure.plusSeconds(interval);
            assertEquals(1, service.due().size());
        }
        clock.now = value[0].firstFailureAt().plusSeconds(86400);
        assertEquals(true, service.status("session", "exercise").interventionRequired());
        clock.now = clock.now.plusSeconds(40 * 86400);
        assertEquals("FAILED", service.status("session", "exercise").state());
        value[0] = new DeletionEntity("exercise", "owner", "workspace", "SUCCEEDED", value[0].requestedAt(), clock.instant(), 5, clock.instant(), null, null);
        clock.now = clock.now.plusSeconds(7 * 86400);
        assertThrows(BusinessException.class, () -> service.status("session", "exercise"));
    }

    @Test
    void expiryHonorsPersistedPromiseEvenAfterConfigurationReductionAndNeverExpiresActiveExercise() {
        MutableClock clock = new MutableClock();
        Instant until = clock.instant().plusSeconds(30 * 86400);
        DeletionRepository deletions = mock(DeletionRepository.class);
        ExerciseRepository exercises = mock(ExerciseRepository.class);
        IdentityTransactionService identity = mock(IdentityTransactionService.class);
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "workspace", "prepare", "ENDED", 7L, "[]", 1, clock.instant(), null, null, null, null, null));
        when(deletions.retainUntil("exercise")).thenReturn(until);
        DeletionStore service = new DeletionStore(deletions, exercises, identity, clock, new RetentionProperties(20, 1, 7, List.of(60, 300, 1800, 3600), 24, 60), new ExerciseDataService(List.of()));
        clock.now = until.minusNanos(1);
        service.expire("exercise");
        verify(deletions, never()).insert(any());
        clock.now = until;
        service.expire("exercise");
        verify(deletions).insert(any());
        when(exercises.find("active")).thenReturn(new ExerciseEntity("active", "owner", "problem", "workspace", "prepare", "ACTIVE", 7L, "[]", 1, clock.instant(), null, null, null, null, null));
        service.expire("active");
        verify(deletions, never()).retainUntil("active");
    }

    @Test
    void cleanupWaitsForReconciliationAndRestartKeepsThePersistedRetrySchedule() {
        MutableClock clock = new MutableClock();
        DeletionRepository deletions = mock(DeletionRepository.class);
        ExerciseRepository exercises = mock(ExerciseRepository.class);
        IdentityTransactionService identity = mock(IdentityTransactionService.class);
        when(identity.authorization("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, clock.instant().plusSeconds(60)));
        DeletionEntity[] value = {new DeletionEntity("exercise", "owner", "workspace", "PENDING", clock.instant(), null, 0, clock.instant(), null, null)};
        String[] claim = {null};
        when(deletions.find("exercise")).thenAnswer(call -> value[0]);
        when(deletions.claim(eq("exercise"), any(), any(), anyString())).thenAnswer(call -> {
            if (clock.instant().isBefore(value[0].nextAttemptAt()) || value[0].state().equals("SUCCEEDED")) return 0;
            claim[0] = call.getArgument(3);
            value[0] = new DeletionEntity("exercise", "owner", "workspace", "RUNNING", value[0].requestedAt(), null, value[0].attempts(), call.getArgument(2), value[0].firstFailureAt(), null);
            return 1;
        });
        doAnswer(call -> {
            if (call.getArgument(1).equals(claim[0])) value[0] = new DeletionEntity("exercise", "owner", "workspace", "FAILED", value[0].requestedAt(), null, value[0].attempts() + 1, call.getArgument(3), clock.instant(), call.getArgument(4));
            return null;
        }).when(deletions).failed(anyString(), anyString(), any(), any(), anyString());
        when(deletions.complete(eq("exercise"), anyString(), any())).thenAnswer(call -> {
            if (!call.getArgument(1).equals(claim[0])) return 0;
            value[0] = new DeletionEntity("exercise", "owner", "workspace", "SUCCEEDED", value[0].requestedAt(), clock.instant(), 0, clock.instant(), null, null);
            return 1;
        });
        when(deletions.workspaceInstances("exercise")).thenReturn(List.of("previous-preparation", "workspace"));
        when(deletions.unresolved("exercise")).thenReturn(1);
        RetentionProperties policy = new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60);
        DeletionStore service = new DeletionStore(deletions, exercises, identity, clock, policy, new ExerciseDataService(List.of()));
        SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
        when(sandbox.deleteWorkspace("previous-preparation")).thenReturn(WorkspaceDeletionReceipt.newBuilder().setWorkspaceInstanceId("previous-preparation").setComplete(true).build());
        when(sandbox.deleteWorkspace("workspace")).thenReturn(WorkspaceDeletionReceipt.newBuilder().setWorkspaceInstanceId("workspace").setComplete(true).build());
        new DeletionCoordinator(service, sandbox).drive(value[0]);
        assertEquals("RECONCILIATION_REQUIRED", service.status("session", "exercise").failureCode());
        verify(sandbox, never()).deleteWorkspace(anyString());
        DeletionStore restarted = new DeletionStore(deletions, exercises, identity, clock, policy, new ExerciseDataService(List.of()));
        DeletionCoordinator worker = new DeletionCoordinator(restarted, sandbox);
        worker.drive(value[0]);
        assertEquals("FAILED", restarted.status("session", "exercise").state());
        clock.now = clock.instant().plusSeconds(60);
        when(deletions.unresolved("exercise")).thenReturn(0);
        when(deletions.cleanupStates("exercise")).thenReturn(List.of(new ExecutionCleanupDTO(true, "SUCCEEDED", "NOT_CREATED")));
        worker.drive(value[0]);
        assertEquals("SUCCEEDED", restarted.status("session", "exercise").state());
        verify(sandbox).deleteWorkspace("workspace");
        verify(sandbox).deleteWorkspace("previous-preparation");
        verify(identity).lockResourceOwner("owner");
        verify(deletions).eraseMutationPayloads("exercise");
        verify(deletions, never()).expireMutationReceipts(any());
        Instant completed = clock.instant();
        clock.now = completed.plusSeconds(7 * 86400);
        restarted.expireReceipts();
        var expiry = org.mockito.Mockito.inOrder(deletions);
        expiry.verify(deletions).expireMutationReceipts(completed);
        expiry.verify(deletions).expireReceipts(completed);
    }

    @Test
    void confirmedCancellationWithoutRegistrationAllowsDeletionButUnknownExecutionDoesNot() {
        MutableClock clock = new MutableClock();
        DeletionRepository deletions = mock(DeletionRepository.class);
        DeletionStore service = new DeletionStore(deletions, mock(ExerciseRepository.class), mock(IdentityTransactionService.class), clock,
            new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), new ExerciseDataService(List.of()));
        when(deletions.cleanupStates("exercise")).thenReturn(List.of(new ExecutionCleanupDTO(true, "SUCCEEDED", "NOT_CREATED")));
        assertEquals(true, service.reconciled("exercise"));
        when(deletions.cleanupStates("exercise")).thenReturn(List.of(new ExecutionCleanupDTO(false, "FAILED", "UNKNOWN")));
        assertEquals(false, service.reconciled("exercise"));
        when(deletions.cleanupStates("exercise")).thenReturn(List.of(new ExecutionCleanupDTO(true, null, null)));
        assertEquals(false, service.reconciled("exercise"));
    }

    @Test
    void historyLimitRejectsNewCreationButDoesNotEvictExistingExercises() {
        MutableClock clock = new MutableClock();
        ExerciseRepository exercises = mock(ExerciseRepository.class);
        IdentityTransactionService identity = mock(IdentityTransactionService.class);
        when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, clock.instant().plusSeconds(60)));
        when(exercises.retainedHistory("owner")).thenReturn(20);
        ExerciseTransactionService service = new ExerciseTransactionService(exercises, identity, mock(ProblemTransactionService.class), clock,
            mock(IdentitySecrets.class), mock(WorkspaceCoordinationStore.class), new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), mock(StorageCapacityStore.class), mock(ExerciseModelService.class));
        assertThrows(BusinessException.class, () -> service.create("session", "version", "key"));
        verify(exercises, never()).insert(any());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }

    @Test
    void onlyOwnerCanDeleteEndedExerciseAndRepeatedRequestPreservesOriginalAcceptance() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        DeletionRepository deletions = mock(DeletionRepository.class);
        ExerciseRepository exercises = mock(ExerciseRepository.class);
        IdentityTransactionService identity = mock(IdentityTransactionService.class);
        when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(60)));
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "workspace", "prepare", "ENDED", 7L, "[]", 1, now, now, now, null, null, null));
        DeletionEntity[] stored = {null};
        doAnswer(call -> { stored[0] = call.getArgument(0); return null; }).when(deletions).insert(any());
        when(deletions.find("exercise")).thenAnswer(call -> stored[0]);
        DeletionStore service = new DeletionStore(deletions, exercises, identity, Clock.fixed(now, ZoneOffset.UTC), new RetentionProperties(20, 30, 7, List.of(60, 300, 1800, 3600), 24, 60), new ExerciseDataService(List.of()));
        assertEquals("PENDING", service.request("session", "exercise", "delete").state());
        assertEquals(now, service.request("session", "exercise", "delete").requestedAt());
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "other", "problem", "workspace", "prepare", "ENDED", 7L, "[]", 1, now, now, now, null, null, null));
        assertThrows(BusinessException.class, () -> service.request("session", "exercise", "delete"));
    }
}
