package ai.codecraft.modules.modelaccess.model.dto;

import java.util.List;

public record ModelConfigurationsResponse(List<ModelConfigurationResponse> models, String nextCursor) {}
