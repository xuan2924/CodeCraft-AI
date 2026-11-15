package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotTaskResponse(String id, String exerciseId, String state, String reason,
    boolean stopRequested, Instant createdAt, Instant completedAt, String conversationId) {
    public CopilotTaskResponse(String id, String exerciseId, String state, String reason,
        boolean stopRequested, Instant createdAt, Instant completedAt) {
        this(id, exerciseId, state, reason, stopRequested, createdAt, completedAt, exerciseId);
    }
}
