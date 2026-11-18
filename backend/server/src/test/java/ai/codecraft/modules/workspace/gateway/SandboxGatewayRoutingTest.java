package ai.codecraft.modules.workspace.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.workspace.model.dto.SandboxNodeDTO;
import ai.codecraft.modules.workspace.model.dto.StorageScope;
import ai.codecraft.modules.workspace.service.SandboxRoutingStore;
import ai.codecraft.workspace.v1.GrepWorkspaceRequest;
import ai.codecraft.workspace.v1.WorkspaceStorageGrpc;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxGatewayRoutingTest {
    @Test void readsFencesCleanupAndMeteringUseThePinnedClient() {
        var clients = mock(SandboxClients.class); var routes = mock(SandboxRoutingStore.class);
        var node = new SandboxNodeDTO("second", "two:9090", "b".repeat(64), "B".repeat(26), 0, 4, Instant.MAX, 0, true);
        var rpc = mock(WorkspaceStorageGrpc.WorkspaceStorageBlockingStub.class);
        when(routes.forInstance("workspace")).thenReturn(node); when(routes.forExercise("exercise")).thenReturn(node);
        when(clients.client(node)).thenReturn(rpc);
        when(rpc.measureExerciseStorage(any())).thenReturn(ai.codecraft.workspace.v1.StorageMeasurement.newBuilder()
            .setExerciseId("exercise").setMeterEpoch("B".repeat(26)).setSequence(1).setBytes(1).build());
        var gateway = new SandboxWorkspaceGateway(clients, routes);
        gateway.read("workspace", "src/Main.java", 3);
        gateway.grep(GrepWorkspaceRequest.newBuilder().setWorkspaceInstanceId("workspace").setExpectedRevision(3).setPattern("needle").build());
        gateway.gate("workspace"); gateway.closeGate("workspace", "transition", 3);
        gateway.collectWorkspace("workspace", 3, List.of(3L)); gateway.deleteWorkspace("workspace");
        gateway.measureStorage(new StorageScope("exercise", List.of("workspace"), List.of()));
        verify(rpc).readWorkspaceFile(any()); verify(rpc).grepWorkspace(any()); verify(rpc).queryGate(any());
        verify(rpc).closeGate(any()); verify(rpc).collectWorkspace(any()); verify(rpc).deleteWorkspace(any());
        verify(rpc).measureExerciseStorage(any()); verify(routes, never()).require("default");
        when(routes.forInstance("workspace")).thenThrow(new IllegalStateException("node unavailable"));
        assertThatThrownBy(() -> gateway.read("workspace", "src/Main.java", 3)).hasMessage("node unavailable");
    }
}
