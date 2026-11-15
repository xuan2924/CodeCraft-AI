package ai.codecraft.modules.copilot.model.dto;

import java.util.UUID;

public record CopilotForkRequest(UUID sourceConversationId, UUID throughMessageId) {}
