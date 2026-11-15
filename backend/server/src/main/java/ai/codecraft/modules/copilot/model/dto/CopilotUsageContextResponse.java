package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotUsageContextResponse(String state, String turnId, String modelId, Integer contextWindow,
    String windowSource, String inputTokens, Instant dispatchedAt) { }
