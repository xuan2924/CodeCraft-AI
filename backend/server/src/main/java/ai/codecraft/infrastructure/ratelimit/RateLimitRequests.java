package ai.codecraft.infrastructure.ratelimit;

import java.util.List;
import java.util.Objects;

public final class RateLimitRequests {
    private RateLimitRequests() { }

    public static List<RateLimitPolicy> validate(List<RateLimitPolicy> policies) {
        if (policies == null || policies.isEmpty() || policies.size() > 16 || policies.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Expected 1 to 16 rate limit policies");
        }
        return policies.stream().distinct().toList();
    }
}
