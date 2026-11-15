package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotCompactionEntity(String id, long sequence, String exerciseId, String userId, String sessionHash,
    long actorAuthVersion, String anchorTaskId, String boundTaskId, String requestKey, String fingerprint, String triggerKind,
    String state, boolean stopRequested, Long sourceVersion, String sourceSummaryId, Long throughSequence,
    String sourceIdsPayload, String summaryPayload, String failureCode, int attemptCount,
    String workerId, String workerTaskId, Instant workerNotAfter, Instant createdAt, Instant completedAt) {
    @Override public String toString() { return "CopilotCompactionEntity[id=" + id + ", state=" + state + "]"; }
}
