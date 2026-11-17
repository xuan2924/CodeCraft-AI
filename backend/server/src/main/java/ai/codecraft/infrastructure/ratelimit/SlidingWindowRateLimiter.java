package ai.codecraft.infrastructure.ratelimit;

import java.util.List;

/** Durable admission; charges each unsaturated bucket even when another bucket rejects. */
public interface SlidingWindowRateLimiter {
    boolean admit(List<RateLimitPolicy> policies);
}
