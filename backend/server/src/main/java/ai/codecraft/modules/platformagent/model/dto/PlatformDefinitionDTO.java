package ai.codecraft.modules.platformagent.model.dto;

import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationResponse;
import ai.codecraft.modules.skills.model.dto.SkillPackageDTO;
import java.util.List;

/** Non-secret immutable published content; never references a user's private configurations. */
public record PlatformDefinitionDTO(ModelConnectionResponse connection, ModelConfigurationResponse model,
    String instructions, List<SkillPackageDTO> skills, String policyVersion) {
    public PlatformDefinitionDTO { skills = List.copyOf(skills); }
}
