package ai.codecraft.modules.workspace.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.infrastructure.ratelimit.ConcurrentLeaseLimiter;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerminalLeaseTest {
    @Test void admissionAndRenewalFailuresCloseRatherThanBypassQuota() {
        var leases = mock(ConcurrentLeaseLimiter.class);
        var connections = new TerminalConnections(leases);
        var stops = new AtomicInteger();
        when(leases.acquire(anyString(), anyString())).thenReturn(false).thenThrow(new IllegalStateException("offline")).thenReturn(true);
        assertThat(connections.register("task", "exercise", stops::incrementAndGet)).isFalse();
        assertThatThrownBy(() -> connections.register("task", "exercise", stops::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        assertThat(connections.register("task", "exercise", () -> { stops.incrementAndGet(); connections.remove("task"); })).isTrue();
        when(leases.renew(anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        doThrow(new IllegalStateException("offline")).when(leases).release(anyString(), anyString());
        connections.renew();
        assertThat(stops).hasValue(1);
        connections.renew();
        assertThat(stops).hasValue(1);
    }
}
