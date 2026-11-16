package ai.codecraft.modules.platformagent.model.entity;

import java.time.Instant;

public record PlatformCheckEntity(String id, String versionId, long credentialRevision, String actorId, String sessionHash,
    String state, Instant createdAt, Instant deadlineAt, Instant finishedAt, String errorCode, String priceSnapshot, String usageReceipt) {
    @Override public String toString() { return "PlatformCheckEntity[REDACTED]"; }
}
