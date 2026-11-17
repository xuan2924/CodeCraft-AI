package ai.codecraft.infrastructure.ratelimit;

import java.time.Instant;

public record RateLimitCharge(String bucketHash, Instant expiresAt) { }
