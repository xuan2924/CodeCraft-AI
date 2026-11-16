package ai.codecraft.modules.platformagent.model.dto;

import ai.codecraft.modules.modelaccess.model.dto.ModelConnectionRequest;
import ai.codecraft.modules.modelaccess.model.dto.ModelConfigurationRequest;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.skills.model.dto.SkillPackageRequest;
import java.util.List;

public record PlatformDraftRequest(PlatformRole role, ModelConnectionRequest connection, ModelConfigurationRequest model,
    String instructions, List<SkillPackageRequest> skills) {
    @Override public String toString() { return "PlatformDraftRequest[REDACTED]"; }
}
