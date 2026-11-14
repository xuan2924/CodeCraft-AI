package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotTurnEntity(String id, String taskId, String exerciseId, long ordinal, String state,
    String connectionId, String modelConfigurationId, long connectionConfigurationVersion, long modelConfigurationVersion,
    String protocol, String modelId, int contextWindow, String assistantMessageId, int toolPosition,
    Instant createdAt, Instant notAfter, Instant completedAt, String errorCode) {}
