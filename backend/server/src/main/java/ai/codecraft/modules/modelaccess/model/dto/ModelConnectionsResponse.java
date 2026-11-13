package ai.codecraft.modules.modelaccess.model.dto;

import java.util.List;

public record ModelConnectionsResponse(List<ModelConnectionResponse> connections, String nextCursor) {}
