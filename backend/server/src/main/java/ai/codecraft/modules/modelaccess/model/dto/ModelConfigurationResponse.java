package ai.codecraft.modules.modelaccess.model.dto;

import ai.codecraft.modules.modelaccess.model.ModelThinkingMode;

public record ModelConfigurationResponse(String id, String connectionId, String name, String modelId, int contextWindow, int maxOutputTokens,
    ModelThinkingMode thinkingMode, String reasoningEffort, Integer thinkingBudgetTokens, Double temperature, String version,
    String configurationVersion, String windowSource, boolean deleted) implements ModelCommandResponse {}
