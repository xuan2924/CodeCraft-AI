package ai.codecraft.modules.skills.model.exception;

import ai.codecraft.common.exception.ErrorCode;

public enum SkillErrorCode implements ErrorCode {
    SKILL_INVALID_PACKAGE(400), SKILL_LIMIT_EXCEEDED(422), SKILL_NOT_FOUND(404), SKILL_VERSION_CONFLICT(409),
    SKILL_NAME_CONFLICT(409), SKILL_IDEMPOTENCY_CONFLICT(409), SKILL_STORAGE_UNAVAILABLE(503);

    private final int status;
    SkillErrorCode(int status) { this.status = status; }
    @Override public String code() { return name(); }
    @Override public int status() { return status; }
}
