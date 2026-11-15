package ai.codecraft.modules.copilot.model.dto;

public record CopilotReferenceSelectionDTO(String kind, String source, String version, int offset, int limit, String fingerprint) {}
