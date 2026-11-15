package ai.codecraft.modules.skills.model.dto;

public record SkillResponse(String id, String name, String description, String version, String contentVersion,
    boolean enabled, boolean deleted, long contentBytes) {}
