package ai.codecraft.modules.workspace.model.exception;

import ai.codecraft.common.exception.ErrorCode;

public enum WorkspaceErrorCode implements ErrorCode {
    INVALID_REQUEST(400), RESOURCE_NOT_FOUND(404), ACTIVE_EXERCISE_EXISTS(409),
    INVALID_STATE(409), IDEMPOTENCY_CONFLICT(409), PUBLICATION_CONFLICT(409),
    REVISION_CONFLICT(409), WORKSPACE_UNAVAILABLE(503);

    private final int status;
    WorkspaceErrorCode(int status) { this.status = status; }
    @Override public String code() { return name(); }
    @Override public int status() { return status; }
}
