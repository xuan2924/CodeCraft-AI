package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record WorkspaceHistoryResponse(String exerciseId, List<WorkspaceChangeResponse> changes, String nextBefore) {}
