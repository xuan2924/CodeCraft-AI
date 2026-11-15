package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotFollowupEntity(String id, long sequence, String exerciseId, String conversationId, String userId,
    String sessionHash, String controlHash, String requestKey, String text, String anchorTaskId, String resumeAfter,
    String state, String reason, String taskId, Instant createdAt, Instant completedAt, long generation, long anchorSequence) {
    @Override public String toString() { return "CopilotFollowupEntity[id=" + id + ", state=" + state + "]"; }
}
