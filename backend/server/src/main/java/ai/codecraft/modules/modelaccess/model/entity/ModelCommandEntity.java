package ai.codecraft.modules.modelaccess.model.entity;

import java.time.Instant;

public record ModelCommandEntity(String userId, String kind, String scope, String requestKey, String fingerprint,
    String response, Instant completedAt) {
    @Override public String toString() { return "ModelCommandEntity[kind=" + kind + ", response=REDACTED]"; }
}
