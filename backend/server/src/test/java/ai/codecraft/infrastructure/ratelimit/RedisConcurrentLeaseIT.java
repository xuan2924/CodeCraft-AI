package ai.codecraft.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Requires a dedicated disposable Redis, sharing the existing rate-limit IT opt-in. */
@EnabledIfEnvironmentVariable(named = "RATE_LIMIT_REDIS_IT_RUN", matches = "disposable")
class RedisConcurrentLeaseIT {
    @Test void luaCapacityOwnershipExpiryAndRenewalAcrossNodes() {
        var factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("RATE_LIMIT_REDIS_PORT")));
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        String key = "codecraft:{terminal-quota}:leases";
        try {
            redis.delete(key);
            var first = new ConcurrentLeaseLimiter(redis, Clock.systemUTC(), new RateLimitProperties("redis", 2000));
            var second = new ConcurrentLeaseLimiter(redis, Clock.systemUTC(), new RateLimitProperties("redis", 2000));
            for (int i = 0; i < 62; i++) assertThat(first.acquire("task" + i, "owner")).isTrue();
            var calls = IntStream.range(62, 82).mapToObj(i -> CompletableFuture.supplyAsync(
                () -> (i % 2 == 0 ? first : second).acquire("task" + i, "owner"))).toList();
            assertThat(calls.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count()).isEqualTo(2);
            assertThat(second.acquire("task0", "new")).isFalse();
            assertThat(second.renew("task0", "wrong")).isFalse();
            second.release("task0", "wrong");
            assertThat(first.renew("task0", "owner")).isTrue();
            assertThat(redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, 15000L);
            // Inject an expired row, avoiding timing sleeps while executing actual server-side cleanup.
            redis.opsForHash().put(key, "task0", "{\"token\":\"owner\",\"expires\":0}");
            assertThat(first.renew("task0", "owner")).isFalse();
            assertThat(second.acquire("task0", "new")).isTrue();
            first.release("task0", "owner");
            assertThat(second.renew("task0", "new")).isTrue();
            second.release("task0", "new");
            assertThat(first.acquire("replacement", "owner")).isTrue();
        } finally { redis.delete(key); factory.destroy(); }
    }
}
