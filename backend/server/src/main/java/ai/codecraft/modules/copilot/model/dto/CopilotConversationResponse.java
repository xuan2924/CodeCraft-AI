package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotConversationResponse(String id, String exerciseId, boolean initial, Instant createdAt) {}
