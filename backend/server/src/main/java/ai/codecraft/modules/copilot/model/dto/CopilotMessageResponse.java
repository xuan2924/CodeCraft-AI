package ai.codecraft.modules.copilot.model.dto;

public record CopilotMessageResponse(String sequence, String id, String taskId, String kind, String text, String thinking) {}
