package ai.codecraft.modules.skills.model.entity;

public record SkillVersionEntity(String id, String skillId, String userId, String name, String description, String filesPayload,
    long contentBytes, String contentHash) {
    @Override public String toString() { return "SkillVersionEntity[REDACTED]"; }
}
