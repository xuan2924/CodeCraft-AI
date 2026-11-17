package ai.codecraft.infrastructure.ratelimit.service;

import ai.codecraft.infrastructure.ratelimit.RateLimitBucketCount;
import ai.codecraft.infrastructure.ratelimit.RateLimitCharge;
import ai.codecraft.infrastructure.ratelimit.RateLimitPolicy;
import ai.codecraft.infrastructure.ratelimit.RateLimitProperties;
import ai.codecraft.infrastructure.ratelimit.RateLimitRequests;
import ai.codecraft.infrastructure.ratelimit.SlidingWindowRateLimiter;
import ai.codecraft.infrastructure.ratelimit.SlidingWindowRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "codecraft.rate-limit.backend", havingValue = "postgresql", matchIfMissing = true)
public class PostgresSlidingWindowRateLimiter implements SlidingWindowRateLimiter {
    private final SlidingWindowRepository repository;
    private final Clock clock;
    private final RateLimitProperties properties;

    public PostgresSlidingWindowRateLimiter(SlidingWindowRepository repository, Clock clock, RateLimitProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean admit(List<RateLimitPolicy> policies) {
        List<RateLimitPolicy> unique = RateLimitRequests.validate(policies);
        repository.lock();
        Instant now = Instant.ofEpochMilli(clock.millis());
        repository.expire(now);
        Map<String, Integer> counts = repository.attempts(unique.stream().map(RateLimitPolicy::bucket).toList())
            .stream().collect(Collectors.toMap(RateLimitBucketCount::bucketHash, RateLimitBucketCount::attempts));
        long missing = unique.stream().filter(policy -> !counts.containsKey(policy.bucket())).count();
        if (repository.buckets() + missing > properties.capacity()) return false;
        boolean accepted = true;
        List<RateLimitCharge> charges = new ArrayList<>();
        for (RateLimitPolicy policy : unique) {
            if (counts.getOrDefault(policy.bucket(), 0) >= policy.limit()) accepted = false;
            else charges.add(new RateLimitCharge(policy.bucket(), now.plusMillis(policy.window().toMillis())));
        }
        if (!charges.isEmpty()) repository.charge(charges);
        return accepted;
    }
}
