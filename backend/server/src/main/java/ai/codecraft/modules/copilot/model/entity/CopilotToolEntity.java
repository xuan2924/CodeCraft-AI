package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotToolEntity(String id, String turnId, String taskId, String exerciseId, int position,
    String callId, String name, String arguments, String state, String resultMessageId,
    Instant createdAt, Instant completedAt) {
    @Override public String toString() { return "CopilotToolEntity[id=" + id + ", state=" + state + "]"; }
}
