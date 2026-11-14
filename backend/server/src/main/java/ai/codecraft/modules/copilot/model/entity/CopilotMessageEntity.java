package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotMessageEntity(long sequence, String id, String taskId, String exerciseId,
    String kind, String payload, Instant createdAt) {
    @Override public String toString() { return "CopilotMessageEntity[id=" + id + ", kind=" + kind + "]"; }
}
