package ai.codecraft.modules.identity.model.entity;

import java.time.Instant;

public record CaptchaEntity(String answerHash, Instant expiresAt) {
    @Override public String toString() { return "CaptchaEntity[redacted]"; }
}
