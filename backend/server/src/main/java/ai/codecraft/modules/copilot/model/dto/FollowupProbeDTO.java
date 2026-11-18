package ai.codecraft.modules.copilot.model.dto;

public record FollowupProbeDTO(String id, long sequence, String exercise, String state, boolean dispatchable) { }
