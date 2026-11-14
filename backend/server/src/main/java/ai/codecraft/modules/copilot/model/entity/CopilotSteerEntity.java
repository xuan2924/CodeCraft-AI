package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotSteerEntity(String id, long sequence, String taskId, String exerciseId, String actorId, long actorAuthVersion,
    String requestKey, String fingerprint, String text, String messagePayload, String messageId, String state,
    String turnId, String reason, Instant createdAt, Instant completedAt) {
    @Override public String toString() { return "CopilotSteerEntity[id=" + id + ", state=" + state + "]"; }
}
