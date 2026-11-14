package ai.codecraft.modules.modelaccess.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelCheckRetentionTest {
    @Test
    void failedBatchStopsAndRetriesFromDurableDiscovery() {
        var now = Instant.parse("2026-06-01T00:00:00Z");
        var checks = mock(ModelCheckRepository.class);
        var service = mock(ModelCheckRetentionService.class);
        var retention = new ModelCheckRetention(checks, service, Clock.fixed(now, ZoneOffset.UTC));
        when(checks.retentionCandidates(now.minus(ModelCheckRetentionService.RETENTION))).thenReturn(List.of("first", "second"));
        when(service.deleteExpired("first")).thenThrow(new IllegalStateException("fixture-sensitive-message")).thenReturn(true);
        retention.clean();
        verify(service, never()).deleteExpired("second");
        retention.clean();
        verify(service, times(2)).deleteExpired("first");
        verify(service).deleteExpired("second");
    }
}
