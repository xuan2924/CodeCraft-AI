package ai.codecraft.modules.identity.service;

import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

public final class IdentityPolicy {
    public static final Duration IDLE_LIMIT = Duration.ofDays(7);
    public static final Duration ABSOLUTE_LIMIT = Duration.ofDays(30);
    public static final Duration ANONYMOUS_LIMIT = Duration.ofMinutes(15);
    public static final int ANONYMOUS_CAPACITY = 1000;
    public static final int ATTEMPTS_PER_MINUTE = 5;

    private IdentityPolicy() { }

    public static String username(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{3,32}")) {
            throw new IdentityFailure(IdentityErrorCode.INVALID_USERNAME);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public static void password(String value) {
        if (value == null || value.length() > 256) {
            throw new IdentityFailure(IdentityErrorCode.INVALID_PASSWORD);
        }
        int length = value.codePointCount(0, value.length());
        if (length < 8 || length > 128) throw new IdentityFailure(IdentityErrorCode.INVALID_PASSWORD);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IdentityFailure(IdentityErrorCode.INVALID_PASSWORD);
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IdentityFailure(IdentityErrorCode.INVALID_PASSWORD);
            }
        }
    }

    public static String idempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IdentityFailure(IdentityErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
        return value;
    }

    public static Instant expiresAt(Instant issuedAt, Instant lastActivity) {
        Instant idle = lastActivity.plus(IDLE_LIMIT);
        Instant absolute = issuedAt.plus(ABSOLUTE_LIMIT);
        return idle.isBefore(absolute) ? idle : absolute;
    }
}
