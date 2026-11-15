package ai.codecraft.modules.skills.model.dto;

public record SkillPreparedDTO(SkillPackageDTO contents, String payload, String hash) {
    @Override public String toString() { return "SkillPreparedDTO[REDACTED]"; }
}
