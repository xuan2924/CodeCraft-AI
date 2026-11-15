package ai.codecraft.modules.skills.model.dto;

public record SkillDocumentDTO(String skillId, String versionId, String content) {
    @Override public String toString() { return "SkillDocumentDTO[REDACTED]"; }
}
