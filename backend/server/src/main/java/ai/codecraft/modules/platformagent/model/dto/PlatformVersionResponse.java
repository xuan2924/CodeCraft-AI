package ai.codecraft.modules.platformagent.model.dto;

import ai.codecraft.modules.platformagent.model.PlatformRole;
import java.time.Instant;

public record PlatformVersionResponse(String id, PlatformRole role, String state, String revision, String credentialRevision,
    PlatformDefinitionDTO definition, boolean credentialAvailable, Instant createdAt, Instant publishedAt) { }
