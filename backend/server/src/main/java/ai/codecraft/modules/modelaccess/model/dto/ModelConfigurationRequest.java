package ai.codecraft.modules.modelaccess.model.dto;

import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;

public record ModelConfigurationRequest(String name, String modelId, Integer contextWindow, Integer maxOutputTokens,
    ModelThinkingMode thinkingMode, String reasoningEffort, Integer thinkingBudgetTokens, Double temperature, String expectedVersion) {}
