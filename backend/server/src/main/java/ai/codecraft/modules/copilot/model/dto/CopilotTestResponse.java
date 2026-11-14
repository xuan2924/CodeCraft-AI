package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotTestResponse(String toolId, String taskId, String testTaskId, String revision, String target,
    String state, String resultState, Instant createdAt, Instant completedAt) {}
