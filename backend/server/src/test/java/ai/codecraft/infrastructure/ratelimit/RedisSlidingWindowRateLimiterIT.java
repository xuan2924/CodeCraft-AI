package ai.codecraft.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Requires a dedicated disposable Redis; never point this test at application data. */
@EnabledIfEnvironmentVariable(named = "RATE_LIMIT_REDIS_IT_RUN", matches = "disposable")
class RedisSlidingWindowRateLimiterIT {
    @Test
    void luaAtomicallyEnforcesSlidingWindowsCapacityAndConcurrentAdmission() {
        var server = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("RATE_LIMIT_REDIS_PORT")));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build();
        var factory = new LettuceConnectionFactory(server, client);
        factory.afterPropertiesSet();
        factory.start();
        var redis = new StringRedisTemplate(factory);
        String key = "codecraft:{rate-limit}:windows";
        try {
            redis.delete(key);
            var clock = mock(Clock.class);
            when(clock.millis()).thenReturn(0L);
            var limiter = new RedisSlidingWindowRateLimiter(redis, clock, new RateLimitProperties("redis", 2));
            var account = new RateLimitPolicy("test", "account", Duration.ofMinutes(1), 2);
            assertThat(limiter.admit(List.of(account, account))).isTrue();
            when(clock.millis()).thenReturn(59000L);
            assertThat(limiter.admit(List.of(account))).isTrue();
            when(clock.millis()).thenReturn(60000L);
            assertThat(limiter.admit(List.of(account))).isTrue();
            assertThat(limiter.admit(List.of(account))).isFalse();
            var address = new RateLimitPolicy("test", "address", Duration.ofMinutes(1), 2);
            assertThat(limiter.admit(List.of(account, address))).isFalse();
            assertThat(limiter.admit(List.of(address))).isTrue();
            assertThat(limiter.admit(List.of(address))).isFalse();
            var flood = new RateLimitPolicy("test", "new-key", Duration.ofMinutes(1), 2);
            assertThat(limiter.admit(List.of(flood))).isFalse();
            when(clock.millis()).thenReturn(120000L);
            List<CompletableFuture<Boolean>> calls = IntStream.range(0, 20)
                .mapToObj(ignored -> CompletableFuture.supplyAsync(() -> limiter.admit(List.of(flood)))).toList();
            assertThat(calls.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count()).isEqualTo(2);
        } finally {
            redis.delete(key);
            factory.destroy();
        }
    }
}
