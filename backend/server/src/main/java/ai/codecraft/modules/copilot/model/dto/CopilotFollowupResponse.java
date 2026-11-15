package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotFollowupResponse(String id, String sequence, String exerciseId, String conversationId, String text,
    String state, String reason, String taskId, Instant createdAt, Instant completedAt) {}
