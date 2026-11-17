package ai.codecraft.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisSlidingWindowRateLimiterTest {
    @Test
    void failedOrUnknownRedisOutcomeNeverFallsBack() {
        var redis = mock(StringRedisTemplate.class);
        var limiter = new RedisSlidingWindowRateLimiter(redis, Clock.systemUTC(), new RateLimitProperties("redis", 2));
        var policies = List.of(new RateLimitPolicy("identity", "account", Duration.ofMinutes(1), 5));
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
            .thenReturn(1L, 0L, null).thenThrow(new IllegalStateException("offline"));
        assertThat(limiter.admit(policies)).isTrue();
        assertThat(limiter.admit(policies)).isFalse();
        assertThatThrownBy(() -> limiter.admit(policies)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> limiter.admit(policies)).isInstanceOf(IllegalStateException.class);
    }
}
