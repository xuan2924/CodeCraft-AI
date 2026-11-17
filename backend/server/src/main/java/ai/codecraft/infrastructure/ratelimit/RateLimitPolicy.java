package ai.codecraft.infrastructure.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Caller-owned scope and policy. Keys must not contain credentials. */
public record RateLimitPolicy(String scope, String key, Duration window, int limit) {
    public RateLimitPolicy {
        if (scope == null || scope.isBlank() || scope.length() > 128 || key == null || key.length() > 512
            || window == null || window.toMillis() < 1 || window.compareTo(Duration.ofDays(1)) > 0
            || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid rate limit policy");
        window = Duration.ofMillis(window.toMillis());
    }

    public String bucket() {
        try {
            String value = scope.length() + ":" + scope + key + ":" + window.toMillis() + ":" + limit;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
