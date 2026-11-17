package ai.codecraft.infrastructure.ratelimit;

public record RateLimitBucketCount(String bucketHash, int attempts) { }
