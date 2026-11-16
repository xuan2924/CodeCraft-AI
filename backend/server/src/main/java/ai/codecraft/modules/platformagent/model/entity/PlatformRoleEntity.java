package ai.codecraft.modules.platformagent.model.entity;

import ai.codecraft.modules.platformagent.model.PlatformRole;

public record PlatformRoleEntity(PlatformRole role, String currentVersion, long revision) { }
