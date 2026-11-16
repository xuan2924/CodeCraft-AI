package ai.codecraft.modules.platformagent.model.exception;

import ai.codecraft.common.exception.ErrorCode;

public enum PlatformAgentErrorCode implements ErrorCode {
    PLATFORM_INVALID_REQUEST(400), PLATFORM_VERSION_NOT_FOUND(404), PLATFORM_VERSION_CONFLICT(409),
    PLATFORM_IDEMPOTENCY_CONFLICT(409), PLATFORM_CONFIGURATION_UNAVAILABLE(503), PLATFORM_CHECK_REQUIRED(409),
    PLATFORM_CHECK_ACTIVE(409), PLATFORM_CHECK_RATE_LIMITED(429), PLATFORM_CHECK_NOT_FOUND(404),
    PLATFORM_CHECK_INVALID_RESPONSE(422), PLATFORM_CHECK_INTERRUPTED(409), PLATFORM_CHECK_FEE_REQUIRED(400);
    private final int status;
    PlatformAgentErrorCode(int status) { this.status = status; }
    @Override public String code() { return name(); }
    @Override public int status() { return status; }
}
