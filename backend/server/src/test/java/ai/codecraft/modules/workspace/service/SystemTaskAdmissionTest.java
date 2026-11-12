package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.workspace.v1.StopTaskResponse;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import ai.codecraft.modules.workspace.model.entity.FinalTaskAuthorityEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.TestStopEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SystemTaskAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:10:00Z");

    @Test
    void cancellationRechecksDispatchGenerationAfterAcquiringCoordinationLock() {
        Fixture fixture = new Fixture();
        when(fixture.writes.lock("exercise")).thenAnswer(call -> {
            // The caller's undispatched observation is stale by lock acquisition.
            fixture.row = fixture.task(true, "OLD_HOST");
            return fixture.control;
        });
        SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
        when(sandbox.stopSystemTask(fixture.row)).thenReturn(StopTaskResponse.newBuilder()
            .setCancelledBeforeRegistration(true).setHostEpoch("NEW_HOST").build());
        new TestTaskCoordinator(fixture.store, sandbox, mock(WorkspaceCoordinationStore.class), mock(WorkspaceWriteCoordinator.class)).stopSystem(fixture.row);
        assertFalse(fixture.store.find("task").quiescent());
        assertEquals("INTERRUPTED", fixture.store.find("task").state());
    }

    @Test
    void manualStopRecoveryCannotReleaseOldGenerationFromSystemCancellationTombstone() {
        Fixture fixture = new Fixture();
        fixture.row = fixture.task(true, "OLD_HOST");
        SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
        when(sandbox.queryStop(fixture.row)).thenReturn(StopTaskResponse.newBuilder()
            .setCancelledBeforeRegistration(true).setHostEpoch("NEW_HOST").build());
        TestTaskCoordinator coordinator = new TestTaskCoordinator(fixture.store, sandbox, mock(WorkspaceCoordinationStore.class), mock(WorkspaceWriteCoordinator.class));
        coordinator.tryStop(new TestStopEntity("stop", "task", 3, NOW, "PENDING"));
        assertFalse(fixture.store.find("task").quiescent());
        assertEquals("INTERRUPTED", fixture.store.find("task").state());
    }

    @Test
    void sameGenerationCancellationCanConfirmQuiescence() {
        Fixture fixture = new Fixture();
        fixture.row = fixture.task(true, "SAME_HOST");
        SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
        when(sandbox.queryStop(fixture.row)).thenReturn(StopTaskResponse.newBuilder()
            .setCancelledBeforeRegistration(true).setHostEpoch("SAME_HOST").build());
        new TestTaskCoordinator(fixture.store, sandbox, mock(WorkspaceCoordinationStore.class), mock(WorkspaceWriteCoordinator.class))
            .tryStop(new TestStopEntity("stop", "task", 3, NOW, "PENDING"));
        assertEquals(true, fixture.store.find("task").quiescent());
        assertEquals("STOPPED", fixture.store.find("task").state());
    }

    @Test
    void systemDispatchExpiresAtBudgetEqualityWithoutOpeningTheGate() {
        Fixture fixture = new Fixture();
        when(fixture.finals.authority("task")).thenReturn(new FinalTaskAuthorityEntity("task", "final", "final", NOW));
        when(fixture.finals.find("exercise")).thenReturn(new FinalizationEntity("final", "exercise", 4L, 7L, NOW.minusSeconds(600), NOW,
            3, 600, 5, 30, 1, "task", null, "PENDING", null, null, true));
        assertFalse(fixture.store.claim(fixture.row, "A".repeat(26)));
        assertFalse(fixture.store.find("task").dispatched());
        assertEquals("INTERRUPTED", fixture.store.find("task").state());
    }

    private static final class Fixture {
        final TestTaskRepository tasks = mock(TestTaskRepository.class);
        final WorkspaceWriteRepository writes = mock(WorkspaceWriteRepository.class);
        final FinalizationRepository finals = mock(FinalizationRepository.class);
        final WorkspaceControlEntity control = new WorkspaceControlEntity("exercise", 1, 4L, "FROZEN", null, null, "final");
        TestTaskEntity row = task(false, null);
        final TestTaskStore store;

        Fixture() {
            ExerciseRepository exercises = mock(ExerciseRepository.class);
            ProblemRepository problems = mock(ProblemRepository.class);
            when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "user", "problem", "workspace", "prepare", "FINAL_TESTING", 7L,
                "[]", 1, NOW.minusSeconds(600), NOW.minusSeconds(600), NOW.minusSeconds(300), null, "USER_ENDED", null));
            when(problems.find("problem")).thenReturn(new ProblemVersionEntity("problem", "cart", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", "{}"));
            when(tasks.find("task")).thenAnswer(call -> row);
            when(tasks.lock("task")).thenAnswer(call -> row);
            when(writes.lock("exercise")).thenReturn(control);
            doAnswer(call -> {
                row = new TestTaskEntity(row.id(), row.executionInstanceId(), row.exerciseId(), row.workspaceInstanceId(), row.revision(), row.gateEpoch(), row.notAfter(),
                    row.target(), call.getArgument(1), row.dispatched(), call.getArgument(2), call.getArgument(3), row.hostEpoch(), row.stateVersion());
                return null;
            }).when(tasks).observed(eq("task"), anyString(), anyBoolean(), anyString());
            store = new TestTaskStore(mock(IdentityTransactionService.class), mock(IdentitySecrets.class), exercises, writes, tasks, problems,
                Clock.fixed(NOW, ZoneOffset.UTC), JsonMapper.builder().addModule(new JavaTimeModule()).build(), mock(TaskStreamRepository.class),
                mock(TaskStreamPublisher.class), finals);
        }

        TestTaskEntity task(boolean dispatched, String host) {
            return new TestTaskEntity("task", "execution", "exercise", "workspace", 7, 4, NOW, "base", "ACCEPTED", dispatched, false, null, host, 0);
        }
    }
}
