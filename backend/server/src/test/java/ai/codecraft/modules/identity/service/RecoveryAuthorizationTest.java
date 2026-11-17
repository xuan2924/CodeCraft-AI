package ai.codecraft.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codecraft.infrastructure.ratelimit.SlidingWindowRateLimiter;
import ai.codecraft.modules.identity.model.entity.SessionEntity;
import ai.codecraft.modules.identity.model.entity.UserEntity;
import ai.codecraft.modules.identity.repository.IdentityRepository;
import ai.codecraft.modules.identity.repository.RevocationIntentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RecoveryAuthorizationTest {
    @Test
    void invalidatedAuthorizationIsARecoverableDecisionButStorageFailureIsNot() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var repository = mock(IdentityRepository.class);
        var service = new IdentityTransactionService(repository, Clock.fixed(now, ZoneOffset.UTC),
            mock(IdentityRevocationNotifier.class), mock(RevocationIntentRepository.class), mock(IdentityRevocationBarrier.class), mock(SlidingWindowRateLimiter.class));
        var valid = new SessionEntity("session", "owner", 1, "csrf", now, now, false);
        when(repository.session("session")).thenReturn(valid);
        when(repository.lockUser("owner")).thenReturn(new UserEntity("owner", "alice", "hash", "USER", false, 1));
        assertThat(service.tryAuthorizeMutation("session").userId()).isEqualTo("owner");
        when(repository.session("session")).thenReturn(new SessionEntity("session", "owner", 1, "csrf", now, now, true));
        assertThat(service.tryAuthorizeMutation("session")).isNull();
        when(repository.session("session")).thenReturn(new SessionEntity("session", "owner", 1, "csrf", now.minusSeconds(30 * 24 * 3600), now, false));
        assertThat(service.tryAuthorizeMutation("session")).isNull();
        when(repository.session("session")).thenReturn(valid);
        when(repository.lockUser("owner")).thenReturn(new UserEntity("owner", "alice", "hash", "USER", true, 1));
        assertThat(service.tryAuthorizeMutation("session")).isNull();
        when(repository.session("session")).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.tryAuthorizeMutation("session")).isInstanceOf(IllegalStateException.class);
    }
}
