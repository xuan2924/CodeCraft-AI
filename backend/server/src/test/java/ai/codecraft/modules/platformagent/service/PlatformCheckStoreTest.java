package ai.codecraft.modules.platformagent.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.service.ModelUsageAccounting;
import ai.codecraft.modules.platformagent.config.PlatformAgentProperties;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.model.entity.PlatformCheckEntity;
import ai.codecraft.modules.platformagent.model.entity.PlatformVersionEntity;
import ai.codecraft.modules.platformagent.repository.PlatformAgentRepository;
import ai.codecraft.modules.platformagent.repository.PlatformCheckRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PlatformCheckStoreTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final PlatformAgentRepository versions = mock(PlatformAgentRepository.class);
    private final PlatformCheckRepository checks = mock(PlatformCheckRepository.class);
    private final IdentityTransactionService identity = mock(IdentityTransactionService.class);
    private final PlatformCheckStore store = new PlatformCheckStore(versions, checks, mock(PlatformBindingService.class), identity,
        mock(PlatformDefinitions.class), mock(ModelUsageAccounting.class), Clock.fixed(now, ZoneOffset.UTC), new PlatformAgentProperties(2, 120, 300, 3, 300, 1800, 30));

    @Test
    void lateSuccessCannotCertifyRotatedCredentials() {
        when(checks.find("check")).thenReturn(check(now.plusSeconds(60)));
        when(identity.tryAuthorizeMutation("session")).thenReturn(new SessionAuthorizationDTO("actor", "ADMIN", false, 1, now.plusSeconds(600)));
        when(versions.lock("version")).thenReturn(new PlatformVersionEntity("version", PlatformRole.INTERVIEWER, "{}", "PUBLISHED", 3, 2, "key", "nonce", "cipher", now, now));
        store.finish("check", null, "usage");
        verify(checks).finish("check", "FAILED", now, "PLATFORM_VERSION_CONFLICT", "usage");
    }

    @Test
    void expiredOrRevokedChecksCannotSucceedAndCompletedChecksAreNotReplayed() {
        when(checks.find("check")).thenReturn(check(now));
        when(versions.lock("version")).thenReturn(new PlatformVersionEntity("version", PlatformRole.INTERVIEWER, "{}", "PUBLISHED", 1, 1, "key", "nonce", "cipher", now, now));
        store.finish("check", null, null);
        verify(checks).finish("check", "FAILED", now, "PLATFORM_CHECK_INTERRUPTED", null);
        when(checks.find("check")).thenReturn(new PlatformCheckEntity("check", "version", 1, "actor", "session", "SUCCEEDED", now, now, now, null, null, null));
        store.claim("check");
        verify(checks, never()).claim(anyString(), any(), any(), any());
    }

    private PlatformCheckEntity check(Instant deadline) {
        return new PlatformCheckEntity("check", "version", 1, "actor", "session", "RUNNING", now.minusSeconds(10), deadline, null, null, null, null);
    }
}
