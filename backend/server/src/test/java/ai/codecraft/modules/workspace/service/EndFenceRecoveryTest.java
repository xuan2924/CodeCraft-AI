package ai.codecraft.modules.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceTransitionEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.workspace.v1.GateResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EndFenceRecoveryTest {
    @Test
    void endConvergesPriorTakeoverThenClosesWithoutReopeningItsOwnGate() {
        WorkspaceWriteRepository writes = mock(WorkspaceWriteRepository.class);
        ExerciseRepository exercises = mock(ExerciseRepository.class);
        SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
        Map<String, WorkspaceTransitionEntity> transitions = new HashMap<>();
        transitions.put("takeover", new WorkspaceTransitionEntity("takeover", "exercise", "TAKEOVER", 2L, "OPENING", "new-holder"));
        transitions.put("end", new WorkspaceTransitionEntity("end", "exercise", "END", null, "CLOSING", null));
        WorkspaceControlEntity[] control = {new WorkspaceControlEntity("exercise", 1, 3L, "CLOSED", "old-holder", "takeover", "end")};
        when(writes.transition(anyString())).thenAnswer(call -> transitions.get(call.getArgument(0)));
        when(writes.lock("exercise")).thenAnswer(call -> control[0]);
        when(writes.control("exercise")).thenAnswer(call -> control[0]);
        when(writes.unresolved("exercise")).thenReturn(List.of());
        doAnswer(call -> {
            WorkspaceControlEntity c = control[0];
            control[0] = new WorkspaceControlEntity(c.exerciseId(), c.generation(), c.gateEpoch(), c.gateState(), c.credentialHash(), call.getArgument(1), c.fenceId());
            return null;
        }).when(writes).activate(eq("exercise"), any());
        doAnswer(call -> {
            WorkspaceControlEntity c = control[0];
            control[0] = new WorkspaceControlEntity(c.exerciseId(), c.generation(), call.getArgument(1), call.getArgument(2), c.credentialHash(), c.transitionId(), c.fenceId());
            return null;
        }).when(writes).gate(eq("exercise"), anyLong(), anyString());
        doAnswer(call -> {
            WorkspaceControlEntity c = control[0];
            control[0] = new WorkspaceControlEntity(c.exerciseId(), c.generation() + 1, c.gateEpoch(), c.gateState(), call.getArgument(1), c.transitionId(), c.fenceId());
            return null;
        }).when(writes).holder(eq("exercise"), any());
        doAnswer(call -> {
            WorkspaceTransitionEntity t = transitions.get(call.getArgument(0));
            transitions.put(t.id(), new WorkspaceTransitionEntity(t.id(), t.exerciseId(), t.kind(), call.getArgument(1), t.phase(), t.targetHash()));
            return null;
        }).when(writes).expectation(anyString(), anyLong());
        doAnswer(call -> {
            WorkspaceTransitionEntity t = transitions.get(call.getArgument(0));
            transitions.put(t.id(), new WorkspaceTransitionEntity(t.id(), t.exerciseId(), t.kind(), t.expectedEpoch(), call.getArgument(1), t.targetHash()));
            return null;
        }).when(writes).phase(anyString(), anyString());
        when(exercises.find("exercise")).thenReturn(new ExerciseEntity("exercise", "user", "problem", "workspace", "prepare", "CLOSING", 7L, "[]", 1,
            Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, "USER_ENDED", null));
        List<String> calls = new ArrayList<>();
        when(sandbox.openGate("workspace", "takeover", 3)).thenAnswer(call -> {
            calls.add("open takeover");
            return gate("takeover", 4, "OPEN");
        });
        when(sandbox.gate("workspace")).thenReturn(gate("takeover", 4, "OPEN"));
        when(sandbox.closeGate("workspace", "end", 4)).thenAnswer(call -> {
            calls.add("close end");
            return gate("end", 5, "CLOSED");
        });
        WorkspaceCoordinationStore store = new WorkspaceCoordinationStore(writes, exercises, new ObjectMapper());
        WorkspaceWriteCoordinator coordinator = new WorkspaceWriteCoordinator(store, sandbox, mock(WorkspaceBatchCodec.class));
        coordinator.drive("end");
        coordinator.drive("end");
        assertEquals(List.of("open takeover", "close end"), calls);
        assertEquals("CLOSED", store.control("exercise").gateState());
        assertEquals(null, store.control("exercise").credentialHash());
        assertEquals("end", store.control("exercise").fenceId());
        assertEquals("SUCCEEDED", store.transition("end").phase());
    }

    private static GateResponse gate(String transition, long epoch, String state) {
        return GateResponse.newBuilder().setWorkspaceInstanceId("workspace").setTransitionId(transition).setEpoch(epoch).setState(state).build();
    }
}
