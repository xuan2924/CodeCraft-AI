package ai.codecraft.modules.platformagent.model.dto;

import ai.codecraft.modules.platformagent.model.PlatformRole;

public record PlatformRoleResponse(PlatformRole role, String currentVersion, String revision) { }
