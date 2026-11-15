package ai.codecraft.modules.skills.model.dto;

import java.util.List;

public record SkillPackageDTO(String name, String description, List<SkillFileDTO> files, long contentBytes) {
    public SkillPackageDTO { files = List.copyOf(files); }
    @Override public String toString() { return "SkillPackageDTO[REDACTED]"; }
}
