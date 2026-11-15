package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotDelegationEntity(String toolId, String exerciseId, String parentTaskId, String childTaskId,
    String conversationId, String goal, String state, String resultPayload, String resultMessageId,
    Instant createdAt, Instant completedAt) { }
