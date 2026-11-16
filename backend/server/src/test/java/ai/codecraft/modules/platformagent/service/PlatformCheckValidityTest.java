package ai.codecraft.modules.platformagent.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.entity.PlatformCheckEntity;
import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import ai.codecraft.modules.platformagent.repository.PlatformCheckRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PlatformCheckValidityTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final PlatformCheckRepository checks = mock(PlatformCheckRepository.class);
    private final PlatformCheckValidity validity = new PlatformCheckValidity(checks, Clock.fixed(now, ZoneOffset.UTC), new PlatformAgentProperties(2, 120, 300, 3, 300, 1800, 30));
    private final PlatformVersionEntity version = new PlatformVersionEntity("version", PlatformRole.INTERVIEWER, "{}", "PUBLISHED", 1, 2, "key", "nonce", "cipher", now, now);

    @Test
    void expiryBlocksPublicationButDoesNotDemandRecurringPaidProbesAfterPublication() {
        when(checks.latest("version")).thenReturn(success(2, now.minusSeconds(1800)));
        assertThatThrownBy(() -> validity.requireChecked(version, true)).isInstanceOf(BusinessException.class);
        validity.requireChecked(version, false);
        when(checks.latest("version")).thenReturn(success(2, now.minusSeconds(1799)));
        validity.requireChecked(version, true);
    }

    @Test
    void oldCredentialCheckCannotAdmitNewBindings() {
        when(checks.latest("version")).thenReturn(success(1, now));
        assertThatThrownBy(() -> validity.requireChecked(version, false)).isInstanceOf(BusinessException.class);
        when(checks.latest("version")).thenReturn(success(2, now));
        validity.requireChecked(version, false);
    }

    private PlatformCheckEntity success(long credential, Instant finished) {
        return new PlatformCheckEntity("check", "version", credential, "actor", "session", "SUCCEEDED", finished, finished, finished, null, null, null);
    }
}
