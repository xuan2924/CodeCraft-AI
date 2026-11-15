package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotUsageContextDTO(String turnId, String modelId, int contextWindow, Long contextVersion,
    String inputTokens, Instant dispatchedAt) { }
