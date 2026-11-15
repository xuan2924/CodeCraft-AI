package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotDelegationResponse(String toolId, String parentTaskId, String conversationId, String goal,
    String state, CopilotTaskResponse child, String resultPayload, Instant createdAt, Instant completedAt) { }
