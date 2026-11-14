package ai.codecraft.modules.copilot.model.dto;

public record CopilotDeliveryDTO(String userId, CopilotTaskResponse task, String lastSequence, boolean textHistory, CopilotPartialResponse partial) {}
