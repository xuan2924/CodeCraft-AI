package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotBtwResponse(String exerciseId, String parentConversationId, String conversationId, Instant createdAt) {}
