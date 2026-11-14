package ai.codecraft.modules.modelaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;

import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.entity.ModelCheckEntity;
import ai.codecraft.modules.modelaccess.repository.ModelCheckRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ModelCheckRetentionServiceTest {
    private final Instant now = Instant.parse("2026-06-01T00:00:00Z");
    private final ModelCheckRepository checks = mock(ModelCheckRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final ModelCheckRetentionService service = new ModelCheckRetentionService(checks, identity, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void deletesEligibleHistoryOnlyAfterOwnerLockAndEligibilityRecheck() {
        when(checks.find("check")).thenReturn(new ModelCheckEntity("check", "owner", "connection", "model", 1, 1, "FAILED", now));
        when(checks.retentionEligible("check", now.minus(Duration.ofDays(30)))).thenReturn(true);
        assertThat(service.deleteExpired("check")).isTrue();
        var ordered = inOrder(identity, checks);
        ordered.verify(checks).find("check");
        ordered.verify(identity).lockResourceOwner("owner");
        ordered.verify(checks).retentionEligible("check", now.minus(Duration.ofDays(30)));
        ordered.verify(checks).deleteReuse("check");
        ordered.verify(checks).deleteRequests("check");
        ordered.verify(checks).deleteAuthority("check");
        ordered.verify(checks).deleteCheck("check");
    }

    @Test
    void staleDiscoveryCannotDeleteActiveRecentOrReferencedHistory() {
        when(checks.find("check")).thenReturn(new ModelCheckEntity("check", "owner", "connection", "model", 1, 1, "RUNNING", now));
        assertThat(service.deleteExpired("check")).isFalse();
        verify(checks, never()).deleteCheck(anyString());
        verify(checks, never()).deleteRequests(anyString());
    }

    @Test
    void repeatedCleanupOfMissingHistoryIsHarmless() {
        assertThat(service.deleteExpired("missing")).isFalse();
        verify(identity, never()).lockResourceOwner(anyString());
    }
}
