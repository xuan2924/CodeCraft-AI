package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.workspace.config.SandboxNodesProperties;
import ai.codecraft.modules.workspace.config.WorkspaceProperties;
import ai.codecraft.modules.workspace.model.dto.SandboxNodeDTO;
import ai.codecraft.modules.workspace.repository.SandboxNodeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxRoutingTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private SandboxNodeDTO node(String id, int active, int reserved, boolean healthy) {
        return new SandboxNodeDTO(id, id + ":9090", "a".repeat(64), "A".repeat(26), active, 4, now.plusSeconds(healthy ? 15 : -1), reserved, true);
    }
    @Test void choosesMostFreeHealthyNodeAndNeverFallsBackForPinnedExercise() {
        var busy = node("default", 3, 0, true); var free = node("secondary", 0, 1, true);
        assertThat(SandboxRoutingStore.select(List.of(busy, free, node("offline", 0, 0, false)), now)).isEqualTo(free);
        assertThatThrownBy(() -> SandboxRoutingStore.select(List.of(node("offline", 0, 0, false), node("full", 4, 0, true)), now))
            .hasMessage("WORKSPACE_UNAVAILABLE");
        var repository = mock(SandboxNodeRepository.class);
        var store = new SandboxRoutingStore(repository, Clock.fixed(now, ZoneOffset.UTC));
        when(repository.candidates()).thenReturn(List.of(busy, free));
        store.bind("new"); verify(repository).bind("new", "secondary");
        when(repository.bound("existing")).thenReturn("default");
        when(repository.node("default")).thenReturn(node("default", 0, 0, false));
        store.bind("existing");
        verify(repository, never()).bind(org.mockito.ArgumentMatchers.eq("existing"), anyString());
        assertThatThrownBy(() -> store.forExercise("existing")).hasMessage("WORKSPACE_UNAVAILABLE");
        assertThatThrownBy(() -> store.forInstance("unregistered")).hasMessage("WORKSPACE_UNAVAILABLE");
    }
    @Test void reachableButBlockedNodeStillAllowsRecoveryNeverNewDispatch() {
        var repository = mock(SandboxNodeRepository.class);
        var blocked = new SandboxNodeDTO("default", "one:9090", "a".repeat(64), "A".repeat(26), 0, 4, now.plusSeconds(15), 0, false);
        when(repository.node("default")).thenReturn(blocked);
        when(repository.instance("workspace")).thenReturn("default");
        when(repository.bound("exercise")).thenReturn("default");
        var store = new SandboxRoutingStore(repository, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(store.forInstance("workspace")).isEqualTo(blocked);
        assertThatThrownBy(() -> store.requireExecutionReady("workspace")).hasMessage("WORKSPACE_UNAVAILABLE");
        assertThatThrownBy(() -> store.admitExecution("exercise")).hasMessage("WORKSPACE_UNAVAILABLE");
        assertThatThrownBy(() -> SandboxRoutingStore.select(List.of(blocked), now)).hasMessage("WORKSPACE_UNAVAILABLE");
        var available = node("default", 0, 0, true);
        when(repository.node("default")).thenReturn(available);
        when(repository.occupied("default")).thenReturn(4);
        assertThatThrownBy(() -> store.admitExecution("exercise")).hasMessage("WORKSPACE_UNAVAILABLE");
    }
    @Test void configurationRequiresUniqueNodesAndLegacyDefault() {
        var fallback = new WorkspaceProperties("", "", 9090, "localhost");
        assertThat(new SandboxNodesProperties(null).endpoints(fallback)).hasSize(1);
        assertThat(new SandboxNodesProperties("default@one:9090,second@two:9090").endpoints(fallback)).hasSize(2);
        for (String invalid : List.of("second@two:9090", "default@one:9090,default@two:9090", "default@one:9090,second@one:9090", "default@one:0")) {
            assertThatThrownBy(() -> new SandboxNodesProperties(invalid).endpoints(fallback)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
