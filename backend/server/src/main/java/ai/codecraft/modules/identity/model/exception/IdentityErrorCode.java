package ai.codecraft.modules.identity.model.exception;

import ai.codecraft.common.exception.ErrorCode;

public enum IdentityErrorCode implements ErrorCode {
    INVALID_USERNAME(400),
    INVALID_PASSWORD(400),
    INVALID_IDEMPOTENCY_KEY(400),
    INVALID_CURRENT_PASSWORD(400),
    INVALID_REQUEST(400),
    UNAUTHENTICATED(401),
    INVALID_CREDENTIALS(401),
    CSRF_REJECTED(403),
    PASSWORD_CHANGE_REQUIRED(403),
    FORBIDDEN(403),
    USERNAME_EXISTS(409),
    IDEMPOTENCY_CONFLICT(409),
    REQUEST_TOO_LARGE(413),
    AUTH_BUSY(429),
    RATE_LIMITED(429),
    CSRF_CAPACITY(429),
    IDENTITY_UNAVAILABLE(503);

    private final int status;

    IdentityErrorCode(int status) { this.status = status; }
    @Override public int status() { return status; }
    @Override public String code() { return name(); }
}
