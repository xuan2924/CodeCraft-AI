package ai.codecraft.modules.skills.model.entity;

public record SkillEntity(String id, String userId, String name, String currentVersionId, long version, boolean enabled, boolean deleted) {}
