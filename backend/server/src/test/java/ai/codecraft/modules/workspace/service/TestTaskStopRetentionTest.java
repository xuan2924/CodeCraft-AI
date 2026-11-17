package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.TestStopEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TestTaskStopRetentionTest {
    @Test
    void alreadyTerminalStopIsAnObservationAndDoesNotConsumeAnotherReceipt() {
        var fixture = new Fixture("STOPPED");
        assertNull(fixture.store.acceptStop("session", "exercise", "task", "new-key", fixture.credential));
        verify(fixture.exercises, never()).insertOperation(any());
    }

    @Test
    void differentKeysJoinTheAlreadyAcceptedStopRatherThanCreatingUnboundedIntents() {
        var fixture = new Fixture("RUNNING");
        var accepted = new TestStopEntity("original-stop", "task", 1, fixture.now.plusSeconds(60), "PENDING", false);
        when(fixture.tasks.acceptedStop("task")).thenReturn(accepted);
        assertSame(accepted, fixture.store.acceptStop("session", "exercise", "task", "another-key", fixture.credential));
        verify(fixture.exercises, never()).insertOperation(any());
    }

    @Test
    void ownerRecoveryPersistsOneStopWithoutRequiringALostPageCredentialOrOrdinaryCredit() {
        var fixture = new Fixture("RUNNING");
        var stop = fixture.store.acceptRecoveryStop("session", "exercise", "task", "rescue-key");
        org.junit.jupiter.api.Assertions.assertTrue(stop.ownerRecovery());
        when(fixture.tasks.acceptedStop("task")).thenReturn(stop);
        assertSame(stop, fixture.store.acceptRecoveryStop("session", "exercise", "task", "another-key"));
        verify(fixture.exercises, org.mockito.Mockito.times(1)).insertOperation(any());
    }

    @Test
    void recoveryCanPromoteTheExistingIntentAndAnOldPageRejectionCannotUndoIt() {
        var fixture = new Fixture("RUNNING");
        var original = new TestStopEntity("stop", "task", 1, fixture.now.plusSeconds(60), "PENDING", false);
        when(fixture.tasks.acceptedStop("task")).thenReturn(original);
        var promoted = fixture.store.acceptRecoveryStop("session", "exercise", "task", "recovery");
        org.junit.jupiter.api.Assertions.assertTrue(promoted.ownerRecovery());
        verify(fixture.tasks).recoverStopAuthority("stop");
        when(fixture.tasks.stop("stop")).thenReturn(promoted);
        var task = fixture.tasks.lock("task");
        when(fixture.tasks.find("task")).thenReturn(task);
        fixture.store.stopObserved(original, false, false, "");
        verify(fixture.exercises, never()).operationState(any(), any());
        verify(fixture.exercises, never()).insertOperation(any());
    }

    @Test
    void ownerRecoveryStillRejectsOtherOwnersAndNeverCancelsSystemFinalVerification() {
        var fixture = new Fixture("RUNNING");
        when(fixture.identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("other", "USER", false, 1, fixture.now.plusSeconds(60)));
        var rejected = org.junit.jupiter.api.Assertions.assertThrows(ai.codecraft.common.exception.BusinessException.class,
            () -> fixture.store.acceptRecoveryStop("session", "exercise", "task", "recovery"));
        org.junit.jupiter.api.Assertions.assertEquals("RESOURCE_NOT_FOUND", rejected.code());
        when(fixture.identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, fixture.now.plusSeconds(60)));
        when(fixture.finals.authority("task")).thenReturn(mock(ai.codecraft.modules.workspace.model.entity.FinalTaskAuthorityEntity.class));
        var finalRejected = org.junit.jupiter.api.Assertions.assertThrows(ai.codecraft.common.exception.BusinessException.class,
            () -> fixture.store.acceptRecoveryStop("session", "exercise", "task", "recovery"));
        org.junit.jupiter.api.Assertions.assertEquals("INVALID_STATE", finalRejected.code());
        verify(fixture.exercises, never()).insertOperation(any());
    }

    private static final class Fixture {
        final String credential = "A".repeat(43);
        final Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final ExerciseRepository exercises = mock(ExerciseRepository.class);
        final TestTaskRepository tasks = mock(TestTaskRepository.class);
        final IdentityTransactionService identity = mock(IdentityTransactionService.class);
        final FinalizationRepository finals = mock(FinalizationRepository.class);
        final TestTaskStore store;
        Fixture(String state) {
            when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("owner", "USER", false, 1, now.plusSeconds(60)));
            when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "owner", "problem", "workspace", "prepare", "ACTIVE", 1L,
                "[]", 1, now, now, now.plusSeconds(3600), null, null, null));
            var writes = mock(WorkspaceWriteRepository.class);
            when(writes.lock("exercise")).thenReturn(new WorkspaceControlEntity("exercise", 1, 1L, "OPEN", IdentitySecrets.digest(credential), null, null));
            when(tasks.lock("task")).thenReturn(new TestTaskEntity("task", "execution", "exercise", "workspace", 1, 1, now.plusSeconds(60), "base", state, true, state.equals("STOPPED"), null, "host", 1));
            store = new TestTaskStore(new ai.codecraft.modules.workspace.config.ExecutionProperties(4), identity, mock(IdentitySecrets.class), exercises, writes, tasks, mock(ProblemRepository.class), Clock.fixed(now, ZoneOffset.UTC),
                new ObjectMapper(), mock(TaskStreamRepository.class), mock(TaskStreamPublisher.class), finals,
                new StorageProperties(512L * 1024 * 1024, 256L * 1024 * 1024, 20), mock(WorkspaceMaintenanceStore.class), mock(StorageCapacityStore.class));
        }
    }
}
