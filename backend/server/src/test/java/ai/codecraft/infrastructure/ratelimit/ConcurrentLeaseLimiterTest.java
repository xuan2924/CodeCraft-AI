package ai.codecraft.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class ConcurrentLeaseLimiterTest {
    @Test void localQuotaExpiryAndStaleOwnerAreDeterministic() {
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        var limiter = new ConcurrentLeaseLimiter(clock);
        for (int i = 0; i < 64; i++) assertThat(limiter.acquire("task" + i, "old")).isTrue();
        assertThat(limiter.acquire("overflow", "new")).isFalse();
        assertThat(limiter.acquire("task0", "new")).isFalse();
        limiter.release("task0", "wrong");
        assertThat(limiter.acquire("overflow", "new")).isFalse();
        when(clock.millis()).thenReturn(15000L);
        assertThat(limiter.renew("task0", "old")).isFalse();
        assertThat(limiter.acquire("task0", "new")).isTrue();
        limiter.release("task0", "old");
        assertThat(limiter.renew("task0", "new")).isTrue();
        limiter.release("task0", "new");
        assertThat(limiter.renew("task0", "new")).isFalse();
    }

    @Test void redisContractAndFailuresNeverFallBackToLocal() {
        var redis = mock(StringRedisTemplate.class);
        var limiter = new ConcurrentLeaseLimiter(redis, Clock.systemUTC(), new RateLimitProperties("redis", 2000));
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class))).thenAnswer(call -> {
            assertThat(call.<java.util.List<String>>getArgument(1)).containsExactly("codecraft:{terminal-quota}:leases");
            assertThat(call.getArguments()).contains("acquire", "task", "token", "64", "15000");
            RedisScript<Long> script = call.getArgument(0);
            assertThat(script.getScriptAsString()).contains("redis.call('TIME')", "current.token == ARGV[3]", "'PEXPIRE'");
            return 0L;
        });
        assertThat(limiter.acquire("task", "token")).isFalse();
        org.mockito.Mockito.reset(redis);
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
            .thenReturn(null, 2L).thenThrow(new IllegalStateException("offline"));
        for (int i = 0; i < 3; i++) assertThatThrownBy(() -> limiter.acquire("task", "token")).isInstanceOf(IllegalStateException.class);
    }
}
