package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotTaskEntity(String id, String exerciseId, String userId, String sessionHash,
    String requestKey, String fingerprint, String state, String reason, boolean stopRequested,
    String workerId, Instant workerNotAfter, Instant createdAt, Instant completedAt, String conversationId, String lane) {
    public CopilotTaskEntity(String id, String exerciseId, String userId, String sessionHash, String requestKey, String fingerprint,
        String state, String reason, boolean stopRequested, String workerId, Instant workerNotAfter, Instant createdAt, Instant completedAt, String conversationId) {
        this(id, exerciseId, userId, sessionHash, requestKey, fingerprint, state, reason, stopRequested, workerId, workerNotAfter, createdAt, completedAt, conversationId, "MAIN");
    }
    @Override public String toString() { return "CopilotTaskEntity[id=" + id + ", state=" + state + "]"; }
}
