package ai.codecraft.modules.platformagent.model.dto;

import java.util.List;

public record PlatformCatalogResponse(List<PlatformVersionResponse> versions, List<PlatformRoleResponse> roles) { }
