package ai.codecraft.infrastructure.ratelimit.service;

import ai.codecraft.infrastructure.ratelimit.RateLimitBucketCount;
import ai.codecraft.infrastructure.ratelimit.RateLimitCharge;
import ai.codecraft.infrastructure.ratelimit.RateLimitPolicy;
import ai.codecraft.infrastructure.ratelimit.RateLimitProperties;
import ai.codecraft.infrastructure.ratelimit.SlidingWindowRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class PostgresSlidingWindowRateLimiterTest {
    private final Clock clock = mock(Clock.class);
    private final MemoryRepository repository = new MemoryRepository();
    private final PostgresSlidingWindowRateLimiter limiter = new PostgresSlidingWindowRateLimiter(
        repository, clock, new RateLimitProperties(null, 2));

    @Test
    void slidingBoundaryRetainsLaterAttemptsAndDeduplicatesDimensions() {
        RateLimitPolicy policy = policy("account");
        when(clock.millis()).thenReturn(0L);
        assertThat(limiter.admit(List.of(policy, policy))).isTrue();
        when(clock.millis()).thenReturn(59000L);
        assertThat(limiter.admit(List.of(policy))).isTrue();
        assertThat(limiter.admit(List.of(policy))).isFalse();
        when(clock.millis()).thenReturn(60000L);
        assertThat(limiter.admit(List.of(policy))).isTrue();
        assertThat(limiter.admit(List.of(policy))).isFalse();
        when(clock.millis()).thenReturn(119000L);
        assertThat(limiter.admit(List.of(policy))).isTrue();
    }

    @Test
    void distinctKeyFloodCannotEvictActiveBucketsAndExpiryReclaimsCapacity() {
        when(clock.millis()).thenReturn(0L);
        assertThat(limiter.admit(List.of(policy("a"), policy("b")))).isTrue();
        assertThat(limiter.admit(List.of(policy("c")))).isFalse();
        assertThat(repository.buckets()).isEqualTo(2);
        when(clock.millis()).thenReturn(60000L);
        assertThat(limiter.admit(List.of(policy("c")))).isTrue();
        assertThat(repository.buckets()).isEqualTo(1);
    }

    @Test
    void rejectionStillChargesUnsaturatedDimensionsAndStorageFailurePropagates() {
        when(clock.millis()).thenReturn(0L);
        limiter.admit(List.of(policy("a")));
        limiter.admit(List.of(policy("a")));
        assertThat(limiter.admit(List.of(policy("a"), policy("b")))).isFalse();
        assertThat(repository.attempts(List.of(policy("b").bucket())))
            .containsExactly(new RateLimitBucketCount(policy("b").bucket(), 1));
        var broken = mock(SlidingWindowRepository.class);
        when(broken.buckets()).thenThrow(new IllegalStateException("offline"));
        var unavailable = new PostgresSlidingWindowRateLimiter(broken, clock, new RateLimitProperties(null, null));
        assertThatThrownBy(() -> unavailable.admit(List.of(policy("a")))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsAndChargesSixteenDimensionsInOneBatchEach() {
        var batches = mock(SlidingWindowRepository.class);
        var batchLimiter = new PostgresSlidingWindowRateLimiter(batches, clock, new RateLimitProperties(null, 16));
        List<RateLimitPolicy> policies = IntStream.range(0, 16).mapToObj(index -> policy("key-" + index)).toList();
        when(clock.millis()).thenReturn(0L);
        assertThat(batchLimiter.admit(policies)).isTrue();
        verify(batches).lock();
        verify(batches).expire(Instant.EPOCH);
        verify(batches).attempts(policies.stream().map(RateLimitPolicy::bucket).toList());
        verify(batches).buckets();
        verify(batches).charge(policies.stream()
            .map(policy -> new RateLimitCharge(policy.bucket(), Instant.EPOCH.plusSeconds(60))).toList());
        verifyNoMoreInteractions(batches);
    }

    @Test
    void validatesBoundsAndRequiresIndependentCommit() throws Exception {
        assertThat(SlidingWindowRepository.class.getMethod("lock").getAnnotation(Update.class)).isNotNull();
        assertThatThrownBy(() -> limiter.admit(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties("fallback", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy("scope", "key", Duration.ofDays(2), 2))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(PostgresSlidingWindowRateLimiter.class.getMethod("admit", List.class)
            .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private static RateLimitPolicy policy(String key) {
        return new RateLimitPolicy("test", key, Duration.ofMinutes(1), 2);
    }

    private static final class MemoryRepository implements SlidingWindowRepository {
        private final Map<String, List<Instant>> rows = new HashMap<>();
        @Override public void lock() { }
        @Override public void expire(Instant now) {
            rows.values().forEach(values -> values.removeIf(expires -> !expires.isAfter(now)));
            rows.values().removeIf(List::isEmpty);
        }
        @Override public int buckets() { return rows.size(); }
        @Override public List<RateLimitBucketCount> attempts(List<String> buckets) {
            return buckets.stream().filter(rows::containsKey)
                .map(bucket -> new RateLimitBucketCount(bucket, rows.get(bucket).size())).toList();
        }
        @Override public void charge(List<RateLimitCharge> charges) {
            charges.forEach(charge -> rows.computeIfAbsent(charge.bucketHash(), ignored -> new ArrayList<>())
                .add(charge.expiresAt()));
        }
    }
}
