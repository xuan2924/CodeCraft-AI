package ai.codecraft.infrastructure.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("codecraft.rate-limit")
public record RateLimitProperties(String backend, Integer capacity) {
    public RateLimitProperties {
        backend = backend == null ? "postgresql" : backend;
        capacity = capacity == null ? 2000 : capacity;
        if (!backend.equals("postgresql") && !backend.equals("redis")) throw new IllegalArgumentException("Unknown rate limit backend");
        if (capacity < 1 || capacity > 10000) throw new IllegalArgumentException("Invalid rate limit capacity");
    }
}
