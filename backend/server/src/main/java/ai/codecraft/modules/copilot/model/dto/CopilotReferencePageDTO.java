package ai.codecraft.modules.copilot.model.dto;

public record CopilotReferencePageDTO(String kind, String source, String version, int offset, int limit, String fingerprint,
    String text, Integer nextOffset, int retainedBytes, boolean sourceIncomplete, String droppedBytes) {}
