package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.FinalizationProperties;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.WorkspaceTransitionEntity;
import io.grpc.Status;
import ai.codecraft.modules.workspace.model.dto.TestEvidenceResponse;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.workspace.v1.GateResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinalizationStoreTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void budgetExpiryEndsWithoutReleasingUnknownExecutionAndLateResultCannotReverseIt() throws Exception {
        Fixture fixture = new Fixture(1, "unknown");
        TestTaskEntity unknown = fixture.task("unknown", "INTERRUPTED", false, 7);
        when(fixture.tasks.find("unknown")).thenReturn(unknown);
        fixture.clock.now = START.plusSeconds(600);
        assertNull(fixture.service().advance("exercise"));
        assertEquals("UNAVAILABLE", fixture.service().find("exercise").verification());
        assertEquals(START.plusSeconds(600), fixture.service().find("exercise").endedAt());
        assertEquals(false, fixture.tasks.find("unknown").quiescent());
        when(fixture.finals.reusable("exercise", "workspace", 7)).thenReturn(fixture.task("late", "PASSED", true, 7));
        fixture.clock.now = START.plusSeconds(601);
        fixture.service().advance("exercise");
        assertEquals("UNAVAILABLE", fixture.service().find("exercise").verification());
    }

    @Test
    void confirmedResultWinsBudgetExpiryBeforeFinalDecision() throws Exception {
        Fixture fixture = new Fixture(1, "confirmed");
        when(fixture.finals.reusable("exercise", "workspace", 7)).thenReturn(fixture.task("confirmed", "PASSED", true, 7));
        fixture.clock.now = START.plusSeconds(600);
        fixture.service().advance("exercise");
        assertEquals("PASSED", fixture.service().find("exercise").verification());
        assertEquals("confirmed", fixture.service().find("exercise").resultTaskId());
    }

    @Test
    void cancellationSelectionUsesCurrentAllocationNotAnEarlierDriversSnapshot() throws Exception {
        Fixture fixture = new Fixture(1, "current");
        TestTaskEntity current = fixture.task("current", "RUNNING", false, 7);
        TestTaskEntity old = fixture.task("old", "INTERRUPTED", false, 6);
        when(fixture.finals.outstanding("exercise")).thenReturn(List.of(old, current));
        assertEquals(List.of(old), fixture.service().cancellationTargets("exercise"));
        fixture.clock.now = START.plusSeconds(600);
        fixture.service().advance("exercise");
        assertEquals(List.of(old, current), fixture.service().cancellationTargets("exercise"));
    }

    @Test
    void frozenRevisionResultIsReusedButOlderPassingRevisionIsNot() throws Exception {
        Fixture fixture = new Fixture(0, null);
        when(fixture.finals.reusable("exercise", "workspace", 7)).thenReturn(fixture.task("old", "PASSED", true, 6));
        when(fixture.tasks.occupied()).thenReturn(1);
        assertNull(fixture.service().advance("exercise"));
        assertEquals("PENDING", fixture.service().find("exercise").verification());
        when(fixture.finals.reusable("exercise", "workspace", 7)).thenReturn(fixture.task("matching", "PASSED", true, 7));
        fixture.service().advance("exercise");
        assertEquals("PASSED", fixture.service().find("exercise").verification());
        assertEquals("matching", fixture.service().find("exercise").resultTaskId());
        assertEquals(0, fixture.service().find("exercise").attempts());
    }

    @Test
    void environmentFailureWaitsPersistedBackoffAndNeverRunsBesideUnknownTask() throws Exception {
        Fixture fixture = new Fixture(1, "first");
        when(fixture.tasks.find("first")).thenReturn(fixture.task("first", "ENVIRONMENT_ERROR", true, 7));
        fixture.service().advance("exercise");
        assertEquals(START.plusSeconds(5), fixture.service().find("exercise").nextAttemptAt());
        fixture.clock.now = START.plusSeconds(4);
        assertNull(fixture.service().advance("exercise"));
        assertEquals(1, fixture.service().find("exercise").attempts());
        fixture.clock.now = START.plusSeconds(5);
        when(fixture.tasks.occupied()).thenReturn(1);
        assertNull(fixture.service().advance("exercise"));
        assertEquals(1, fixture.service().find("exercise").attempts());
        when(fixture.tasks.occupied()).thenReturn(0);
        TestTaskEntity second = fixture.service().advance("exercise");
        assertEquals(START.plusSeconds(600), second.notAfter());
        assertEquals(2, fixture.service().find("exercise").attempts());
        assertEquals(second.id(), fixture.service().find("exercise").currentTaskId());
    }

    @Test
    void lostFreezeResponseKeepsClosingAndRecoveryStartsBudgetOnlyOnce() {
        Fixture fixture = new Fixture(0, null);
        fixture.value = new FinalizationEntity("final", "exercise", null, null, null, null, 3, 600, 5, 30, 0, null, null, "PENDING", null, null, true);
        WorkspaceControlEntity closed = new WorkspaceControlEntity("exercise", 1, 3L, "CLOSED", null, null, "final");
        when(fixture.writes.lock("exercise")).thenReturn(closed);
        when(fixture.writes.control("exercise")).thenReturn(closed);
        when(fixture.writes.transition("final")).thenReturn(new WorkspaceTransitionEntity("final", "exercise", "END", 2L, "SUCCEEDED", null));
        when(fixture.writes.unresolved("exercise")).thenReturn(List.of());
        when(fixture.finals.outstanding("exercise")).thenReturn(List.of());
        when(fixture.exercises.find("exercise")).thenReturn(fixture.exercise("CLOSING"));
        when(fixture.tasks.occupied()).thenReturn(1);
        when(fixture.finals.frozen(eq("exercise"), eq(4L), eq(7L), any(), any())).thenAnswer(call -> {
            fixture.value = new FinalizationEntity("final", "exercise", 4L, 7L, call.getArgument(3), call.getArgument(4), 3, 600, 5, 30, 0, null, null, "PENDING", null, null, true);
            return 1;
        });
        SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
        GateResponse receipt = GateResponse.newBuilder().setWorkspaceInstanceId("workspace").setTransitionId("final").setState("FROZEN").setEpoch(4).setFrozenRevision(7).build();
        when(sandbox.freeze("workspace", "final", 3)).thenThrow(Status.UNAVAILABLE.asRuntimeException()).thenReturn(receipt);
        WorkspaceCoordinationStore coordination = new WorkspaceCoordinationStore(fixture.writes, fixture.exercises, fixture.json);
        WorkspaceWriteCoordinator fences = new WorkspaceWriteCoordinator(coordination, sandbox, mock(WorkspaceBatchCodec.class));
        FinalizationCoordinator coordinator = new FinalizationCoordinator(fixture.service(), coordination, fences, mock(TestTaskCoordinator.class), sandbox, mock(StorageCapacityService.class));
        assertThrows(RuntimeException.class, () -> coordinator.drive("exercise"));
        assertNull(fixture.service().find("exercise").deadlineAt());
        fixture.clock.now = START.plusSeconds(10);
        coordinator.drive("exercise");
        assertEquals(START.plusSeconds(610), fixture.service().find("exercise").deadlineAt());
        fixture.clock.now = START.plusSeconds(20);
        coordinator.drive("exercise");
        assertEquals(START.plusSeconds(610), fixture.service().find("exercise").deadlineAt());
    }

    @Test
    void exhaustedAttemptsEndWithoutDeclaringAnUnknownExecutionQuiescent() throws Exception {
        Fixture fixture = new Fixture(3, "third");
        when(fixture.tasks.find("third")).thenReturn(fixture.task("third", "INTERRUPTED", false, 7));
        fixture.service().advance("exercise");
        assertEquals("UNAVAILABLE", fixture.service().find("exercise").verification());
        assertEquals(false, fixture.tasks.find("third").quiescent());
    }

    @Test
    void freezeCannotConfirmARevisionOlderThanReconciledWrites() {
        Fixture fixture = new Fixture(0, null);
        fixture.value = new FinalizationEntity("final", "exercise", null, null, null, null, 3, 600, 5, 30, 0, null, null, "PENDING", null, null, true);
        when(fixture.exercises.find("exercise")).thenReturn(fixture.exercise("CLOSING"));
        when(fixture.writes.lock("exercise")).thenReturn(new WorkspaceControlEntity("exercise", 1, 3L, "CLOSED", null, null, "final"));
        when(fixture.writes.unresolved("exercise")).thenReturn(List.of());
        GateResponse receipt = GateResponse.newBuilder().setWorkspaceInstanceId("workspace").setTransitionId("final")
            .setState("FROZEN").setEpoch(4).setFrozenRevision(6).build();
        assertThrows(IllegalStateException.class, () -> fixture.service().frozen("exercise", receipt));
    }

    @Test
    void ownerRecoveryEndsWithoutALostPageCredentialAndAllRetryKeysJoinTheSameIntent() {
        var fixture = new Fixture(0, null);
        fixture.value = null;
        when(fixture.identity.authorizeMutation("session")).thenReturn(new ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO("user", "USER", false, 1, START.plusSeconds(60)));
        when(fixture.exercises.find("exercise")).thenReturn(fixture.exercise("ACTIVE"));
        when(fixture.writes.lock("exercise")).thenReturn(new WorkspaceControlEntity("exercise", 1, 1L, "OPEN", IdentitySecrets.digest("lost-credential"), null, null));
        when(fixture.finals.closing("exercise", "USER_ENDED")).thenReturn(1);
        doAnswer(call -> { fixture.value = call.getArgument(0); return null; }).when(fixture.finals).insert(any());
        var accepted = fixture.service().recoveryEnd("session", "exercise", "first");
        assertEquals(accepted, fixture.service().recoveryEnd("session", "exercise", "second"));
        org.mockito.Mockito.verify(fixture.finals, org.mockito.Mockito.times(1)).insert(any());
    }

    @Test
    void ownerRecoveryCannotExposeOrEndAnotherOwnersExistingFinalization() {
        var fixture = new Fixture(0, null);
        when(fixture.identity.authorizeMutation("session")).thenReturn(new ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO("other", "USER", false, 1, START.plusSeconds(60)));
        var failure = assertThrows(ai.codecraft.common.exception.BusinessException.class, () -> fixture.service().recoveryEnd("session", "exercise", "recovery"));
        assertEquals("RESOURCE_NOT_FOUND", failure.code());
        org.mockito.Mockito.verify(fixture.finals, org.mockito.Mockito.never()).insert(any());
    }

    private static final class MutableClock extends Clock {
        private Instant now = START;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }

    /** Storage substitute retains rows across service recreation; no RPC is mocked as business success. */
    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final IdentityTransactionService identity = mock(IdentityTransactionService.class);
        final FinalizationRepository finals = mock(FinalizationRepository.class);
        final ExerciseRepository exercises = mock(ExerciseRepository.class);
        final WorkspaceWriteRepository writes = mock(WorkspaceWriteRepository.class);
        final TestTaskRepository tasks = mock(TestTaskRepository.class);
        final ProblemRepository problems = mock(ProblemRepository.class);
        final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        final ProblemVersionEntity problem = new ProblemVersionEntity("problem", "cart", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", "{}");
        FinalizationEntity value;

        Fixture(int attempts, String task) {
            value = new FinalizationEntity("final", "exercise", 4L, 7L, START, START.plusSeconds(600), 3, 600, 5, 30, attempts, task, null, "PENDING", null, null, true);
            when(finals.find("exercise")).thenAnswer(call -> value);
            when(exercises.find("exercise")).thenReturn(exercise("FINAL_TESTING"));
            when(problems.find("problem")).thenReturn(problem);
            doAnswer(call -> {
                value = copy(value.attempts(), value.currentTaskId(), value.nextAttemptAt(), call.getArgument(2), call.getArgument(3), call.getArgument(1));
                return null;
            }).when(finals).finish(eq("exercise"), any(Instant.class), anyString(), any());
            doAnswer(call -> {
                value = copy(value.attempts(), null, call.getArgument(2), "PENDING", null, null);
                return null;
            }).when(finals).retry(eq("exercise"), anyString(), any(Instant.class));
            when(finals.allocate(eq("exercise"), anyString())).thenAnswer(call -> {
                value = copy(value.attempts() + 1, call.getArgument(1), null, "PENDING", null, null);
                return 1;
            });
            when(finals.frozen(anyString(), anyLong(), anyLong(), any(), any())).thenReturn(1);
        }

        FinalizationStore service() {
            return new FinalizationStore(finals, exercises, writes, tasks, mock(TaskStreamRepository.class), problems,
                identity, mock(IdentitySecrets.class), clock, new FinalizationProperties(3, 600, 5, 30),
                new FinalVerificationPolicy(clock), json, mock(StorageCapacityStore.class));
        }

        ExerciseEntity exercise(String state) {
            return new ExerciseEntity("exercise", "user", "problem", "workspace", "preparation", state, 7L, "[]", 1, START, START,
                START.plusSeconds(1), null, "USER_ENDED", null);
        }

        FinalizationEntity copy(int attempts, String task, Instant next, String result, String resultTask, Instant ended) {
            return new FinalizationEntity(value.id(), value.exerciseId(), value.frozenEpoch(), value.finalRevision(), value.startedAt(), value.deadlineAt(),
                value.maxAttempts(), value.budgetSeconds(), value.firstRetrySeconds(), value.secondRetrySeconds(), attempts, task, next, result, resultTask, ended, true);
        }

        TestTaskEntity task(String id, String state, boolean quiescent, long revision) throws Exception {
            TestTaskResponse response = new TestTaskResponse(id, "exercise", "execution", "workspace", Long.toString(revision), "base", state, "", START, START,
                state.equals("PASSED") ? 0 : null, quiescent, "PENDING", "PENDING", "0", false, List.of(), TestTaskMapper.problem(problem),
                state.equals("PASSED") ? new TestEvidenceResponse("SUREFIRE_XML", "c".repeat(64), 100) : null);
            return new TestTaskEntity(id, "execution", "exercise", "workspace", revision, 4, START.plusSeconds(600), "base", state, true, quiescent,
                json.writeValueAsString(response), "host", 1);
        }
    }
}
