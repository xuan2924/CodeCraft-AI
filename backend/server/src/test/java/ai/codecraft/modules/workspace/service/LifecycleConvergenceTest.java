package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.FinalizationProperties;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceMutationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceTransitionEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.FinalizationRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.workspace.v1.ApplyBatchRequest;
import ai.codecraft.workspace.v1.GateResponse;
import ai.codecraft.workspace.v1.MutationReceipt;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LifecycleConvergenceTest {
    private static final Instant DEADLINE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void expiryRejectsNewManualDispatchAndReconcilesCommittedWriteBeforeFreeze() {
        Fixture fixture = new Fixture(true);
        TestTaskEntity manual = new TestTaskEntity("manual", "execution", "exercise", "workspace", 7, 2, DEADLINE, "base", "ACCEPTED", false, false, null, null, 0);
        when(fixture.tasks.lock("manual")).thenReturn(manual);
        ProblemRepository problems = mock(ProblemRepository.class);
        when(problems.find("problem")).thenReturn(new ProblemVersionEntity("problem", "cart", "1", "a".repeat(64), "sha256:" + "b".repeat(64), "linux/amd64", "{}"));
        TestTaskStore admission = new TestTaskStore(mock(IdentityTransactionService.class), mock(IdentitySecrets.class), fixture.exercises, fixture.writes,
            fixture.tasks, problems, Clock.fixed(DEADLINE, ZoneOffset.UTC), JsonMapper.builder().addModule(new JavaTimeModule()).build(),
            mock(TaskStreamRepository.class), mock(TaskStreamPublisher.class), fixture.finals);
        assertFalse(admission.claim(manual, "A".repeat(26)));
        verify(fixture.tasks, never()).dispatch(anyString(), anyString());
        fixture.store.expire("exercise");
        assertEquals("CLOSING", fixture.state);
        assertNotNull(fixture.control.fenceId());
        fixture.coordinator().drive("exercise");
        assertEquals("SUCCEEDED", fixture.writeState);
        assertEquals(8L, fixture.finalization.finalRevision());
        assertEquals("FROZEN", fixture.control.gateState());
        assertEquals("ENDED", fixture.state);
        assertEquals("UNAVAILABLE", fixture.finalization.verification());
        assertEquals(List.of("close", "query write", "freeze"), fixture.calls);
    }

    @Test
    void lostCloseResponseAndAbsentWriteRecoverWithoutReplayingMutation() {
        Fixture fixture = new Fixture(false);
        fixture.store.end("session", "exercise", "end-key", "c".repeat(43));
        String id = fixture.finalization.id();
        when(fixture.sandbox.closeGate("workspace", id, 2)).thenAnswer(call -> {
            fixture.calls.add("lost close");
            throw Status.UNAVAILABLE.asRuntimeException();
        }).thenAnswer(call -> fixture.closed());
        assertThrows(RuntimeException.class, () -> fixture.coordinator().drive("exercise"));
        assertEquals("PENDING", fixture.writeState);
        assertNull(fixture.finalization.finalRevision());
        assertEquals("CLOSING", fixture.state);
        fixture.coordinator().drive("exercise");
        assertEquals("REJECTED", fixture.writeState);
        assertEquals("WRITE_NOT_COMMITTED", fixture.writeFailure);
        assertEquals(7L, fixture.finalization.finalRevision());
        assertEquals("ENDED", fixture.state);
        verify(fixture.sandbox, never()).apply(any());
        verify(fixture.sandbox, never()).openGate(anyString(), anyString(), anyLong());
    }

    /** Persistent repository substitute and controlled RPC order; recreating workers preserves every row. */
    private static final class Fixture {
        final FinalizationRepository finals = mock(FinalizationRepository.class);
        final ExerciseRepository exercises = mock(ExerciseRepository.class);
        final WorkspaceWriteRepository writes = mock(WorkspaceWriteRepository.class);
        final TestTaskRepository tasks = mock(TestTaskRepository.class);
        final SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
        final WorkspaceBatchCodec codec = mock(WorkspaceBatchCodec.class);
        final Map<String, WorkspaceTransitionEntity> transitions = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final boolean committed;
        final FinalizationStore store;
        final WorkspaceCoordinationStore coordination;
        FinalizationEntity finalization;
        WorkspaceControlEntity control = new WorkspaceControlEntity("exercise", 1, 2L, "OPEN", IdentitySecrets.digest("c".repeat(43)), null, null);
        String state = "ACTIVE";
        String writeState = "PENDING";
        String writeFailure;
        long revision = 7;

        Fixture(boolean committed) {
            this.committed = committed;
            var json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
            IdentityTransactionService identity = mock(IdentityTransactionService.class);
            when(identity.authorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("user", "USER", false, 1, DEADLINE.plusSeconds(1000)));
            Clock clock = Clock.fixed(DEADLINE, ZoneOffset.UTC);
            // One attempt is an allowed bound; environmental failure must release business activity.
            store = new FinalizationStore(finals, exercises, writes, tasks, mock(TaskStreamRepository.class), mock(ProblemRepository.class), identity,
                mock(IdentitySecrets.class), clock, new FinalizationProperties(1, 600, 5, 30), new FinalVerificationPolicy(clock), json);
            coordination = new WorkspaceCoordinationStore(writes, exercises, json);
            when(finals.find("exercise")).thenAnswer(call -> finalization);
            when(exercises.find("exercise")).thenAnswer(call -> exercise());
            when(writes.lock("exercise")).thenAnswer(call -> control);
            when(writes.control("exercise")).thenAnswer(call -> control);
            when(finals.closing(eq("exercise"), anyString())).thenAnswer(call -> { state = "CLOSING"; return 1; });
            doAnswer(call -> { finalization = call.getArgument(0); return null; }).when(finals).insert(any());
            doAnswer(call -> { transitions.put(call.<WorkspaceTransitionEntity>getArgument(0).id(), call.getArgument(0)); return null; }).when(writes).insertTransition(any());
            when(writes.transition(anyString())).thenAnswer(call -> transitions.get(call.getArgument(0)));
            doAnswer(call -> { control = control(control.gateEpoch(), control.gateState(), control.credentialHash(), control.transitionId(), call.getArgument(1)); return null; })
                .when(writes).fence(eq("exercise"), any());
            doAnswer(call -> { control = control(control.gateEpoch(), control.gateState(), control.credentialHash(), call.getArgument(1), control.fenceId()); return null; })
                .when(writes).activate(eq("exercise"), any());
            doAnswer(call -> { control = control(call.getArgument(1), call.getArgument(2), control.credentialHash(), control.transitionId(), control.fenceId()); return null; })
                .when(writes).gate(eq("exercise"), anyLong(), anyString());
            doAnswer(call -> { control = control(control.gateEpoch(), control.gateState(), call.getArgument(1), control.transitionId(), control.fenceId()); return null; })
                .when(writes).holder(eq("exercise"), any());
            doAnswer(call -> {
                WorkspaceTransitionEntity old = transitions.get(call.getArgument(0));
                transitions.put(old.id(), new WorkspaceTransitionEntity(old.id(), old.exerciseId(), old.kind(), call.getArgument(1), old.phase(), old.targetHash())); return null;
            }).when(writes).expectation(anyString(), anyLong());
            doAnswer(call -> {
                WorkspaceTransitionEntity old = transitions.get(call.getArgument(0));
                transitions.put(old.id(), new WorkspaceTransitionEntity(old.id(), old.exerciseId(), old.kind(), old.expectedEpoch(), call.getArgument(1), old.targetHash())); return null;
            }).when(writes).phase(anyString(), anyString());
            when(writes.unresolved("exercise")).thenAnswer(call -> writeState.equals("PENDING") ? List.of(operation()) : List.of());
            when(exercises.operation("write")).thenAnswer(call -> operation());
            doAnswer(call -> { writeState = call.getArgument(1); return null; }).when(exercises).operationState(eq("write"), anyString());
            doAnswer(call -> { writeFailure = call.getArgument(3); return null; }).when(writes).result(eq("write"), any(), any(), any());
            doAnswer(call -> { revision = call.getArgument(1); return null; }).when(writes).observed(eq("exercise"), anyLong(), anyString());
            WorkspaceMutationEntity mutation = new WorkspaceMutationEntity("write", "{}", 2, DEADLINE, true, null, null, null);
            when(writes.mutation("write")).thenReturn(mutation);
            when(codec.request(any(), eq(mutation))).thenReturn(ApplyBatchRequest.newBuilder().setExpectedRevision(7).build());
            when(sandbox.gate("workspace")).thenAnswer(call -> gate(finalization.id(), 2, "OPEN"));
            when(sandbox.closeGate(eq("workspace"), anyString(), eq(2L))).thenAnswer(call -> closed());
            when(sandbox.mutation("workspace", "write")).thenAnswer(call -> {
                calls.add("query write");
                return committed ? MutationReceipt.newBuilder().setWorkspaceInstanceId("workspace").setRequestId("write").setOperationId("write")
                    .setFingerprint("a".repeat(64)).setRevision(8).setChanged(true).build() : null;
            });
            when(sandbox.query("workspace")).thenReturn(WorkspaceSnapshot.newBuilder().setWorkspaceInstanceId("workspace").setPrepared(true).setRevision(8).build());
            when(sandbox.freeze(eq("workspace"), anyString(), eq(3L))).thenAnswer(call -> {
                assertFalse(writeState.equals("PENDING")); calls.add("freeze");
                return gate(finalization.id(), 4, "FROZEN").toBuilder().setFrozenRevision(revision).build();
            });
            when(finals.frozen(eq("exercise"), eq(4L), anyLong(), any(), any())).thenAnswer(call -> {
                finalization = new FinalizationEntity(finalization.id(), "exercise", 4L, call.getArgument(2), call.getArgument(3), call.getArgument(4),
                    1, 600, 5, 30, 1, "environment", null, "PENDING", null, null, true);
                return 1;
            });
            doAnswer(call -> { state = "FINAL_TESTING"; return null; }).when(finals).testing(eq("exercise"), anyLong());
            when(tasks.find("environment")).thenReturn(new TestTaskEntity("environment", "execution", "exercise", "workspace", 8, 4, DEADLINE,
                "base", "ENVIRONMENT_ERROR", true, true, null, null, 0));
            doAnswer(call -> {
                finalization = new FinalizationEntity(finalization.id(), "exercise", finalization.frozenEpoch(), finalization.finalRevision(), finalization.startedAt(),
                    finalization.deadlineAt(), 1, 600, 5, 30, 1, "environment", null, call.getArgument(2), null, call.getArgument(1), true);
                return null;
            }).when(finals).finish(eq("exercise"), any(), anyString(), any());
            doAnswer(call -> { state = "ENDED"; return null; }).when(finals).ended("exercise");
        }

        FinalizationCoordinator coordinator() {
            return new FinalizationCoordinator(store, coordination, new WorkspaceWriteCoordinator(coordination, sandbox, codec), mock(TestTaskCoordinator.class), sandbox);
        }
        GateResponse closed() { calls.add("close"); return gate(finalization.id(), 3, "CLOSED"); }
        WorkspaceControlEntity control(long epoch, String gate, String holder, String transition, String fence) {
            return new WorkspaceControlEntity("exercise", control.generation(), epoch, gate, holder, transition, fence);
        }
        WorkspaceOperationEntity operation() { return new WorkspaceOperationEntity("write", "user", "exercise", "workspace", "WRITE", "exercise", "write-key", "fp", writeState, DEADLINE.minusSeconds(1)); }
        ExerciseEntity exercise() { return new ExerciseEntity("exercise", "user", "problem", "workspace", "prepare", state, revision, "[]", 1, DEADLINE.minusSeconds(100), DEADLINE.minusSeconds(100), DEADLINE, null, null, null); }
        GateResponse gate(String id, long epoch, String gate) { return GateResponse.newBuilder().setWorkspaceInstanceId("workspace").setTransitionId(id).setEpoch(epoch).setState(gate).build(); }
    }
}
