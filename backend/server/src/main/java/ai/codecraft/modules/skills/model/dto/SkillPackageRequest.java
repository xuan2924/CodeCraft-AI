package ai.codecraft.modules.skills.model.dto;

import java.util.List;

public record SkillPackageRequest(int formatVersion, List<SkillFileDTO> files) {
    @Override public String toString() { return "SkillPackageRequest[REDACTED]"; }
}
