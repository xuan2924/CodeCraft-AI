package ai.codecraft.modules.modelaccess.model.exception;

import ai.codecraft.common.exception.ErrorCode;

public enum ModelAccessErrorCode implements ErrorCode {
    MODEL_INVALID_REQUEST(400), MODEL_ENCRYPTION_UNAVAILABLE(503), MODEL_CREDENTIAL_UNAVAILABLE(503),
    MODEL_CONNECTION_NOT_FOUND(404), MODEL_NAME_CONFLICT(409), MODEL_IDEMPOTENCY_CONFLICT(409),
    MODEL_VERSION_CONFLICT(409), MODEL_RECEIPT_EXPIRED(410), MODEL_CONFIGURATION_NOT_FOUND(404), MODEL_STORAGE_UNAVAILABLE(503);

    private final int status;
    ModelAccessErrorCode(int status) { this.status = status; }
    @Override public String code() { return name(); }
    @Override public int status() { return status; }
}
