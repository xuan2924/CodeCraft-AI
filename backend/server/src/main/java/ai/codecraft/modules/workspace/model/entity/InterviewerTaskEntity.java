package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

/** Authorization fields are server-only and never projected into the public task snapshot. */
public record InterviewerTaskEntity(String id, String exerciseId, String userId, String sessionHash, String controlHash,
    String requestKey, String question, String answer, String state, String reason, String platformVersionId,
    String phase, Long revision, Instant createdAt, Instant deadlineAt, Instant completedAt, String usageReceipt) {
    @Override public String toString() { return "InterviewerTaskEntity[REDACTED]"; }
}
