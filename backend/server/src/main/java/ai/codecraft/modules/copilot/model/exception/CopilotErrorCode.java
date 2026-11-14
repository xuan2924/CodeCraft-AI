package ai.codecraft.modules.copilot.model.exception;

import ai.codecraft.common.exception.ErrorCode;

public enum CopilotErrorCode implements ErrorCode {
    COPILOT_INVALID_REQUEST(400), COPILOT_STORAGE_UNAVAILABLE(503), COPILOT_BUSY(409), COPILOT_NOT_FOUND(404),
    COPILOT_IDEMPOTENCY_CONFLICT(409), COPILOT_CONTEXT_REQUIRED(409), COPILOT_INVALID_STATE(409),
    COPILOT_PROPOSAL_NOT_FOUND(404), COPILOT_PROPOSAL_DECIDED(409),
    COPILOT_APPROVAL_MODE_INVALID(400), COPILOT_APPROVAL_MODE_CONFLICT(409),
    COPILOT_STEER_NOT_ACTIVE(409), COPILOT_STEER_LIMIT(429);
    private final int status;
    CopilotErrorCode(int status) { this.status = status; }
    @Override public String code() { return name(); }
    @Override public int status() { return status; }
}
