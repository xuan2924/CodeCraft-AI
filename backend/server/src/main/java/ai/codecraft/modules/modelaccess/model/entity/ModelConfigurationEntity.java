package ai.codecraft.modules.modelaccess.model.entity;

import java.time.Instant;

public record ModelConfigurationEntity(String id, String connectionId, String userId, String name, String modelId, int contextWindow,
    int maxOutputTokens, String thinkingMode, String reasoningEffort, Integer thinkingBudgetTokens, Double temperature, long version,
    long configurationVersion, Instant createdAt, Instant updatedAt, Instant deletedAt) {}
