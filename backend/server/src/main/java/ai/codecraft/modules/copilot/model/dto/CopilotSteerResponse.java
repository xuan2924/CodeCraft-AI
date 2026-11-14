package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotSteerResponse(String id, String sequence, String taskId, String exerciseId, String actorId,
    String text, String state, String turnId, String reason, Instant createdAt, Instant completedAt) {}
