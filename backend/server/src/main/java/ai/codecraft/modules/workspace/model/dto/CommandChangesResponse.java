package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record CommandChangesResponse(String commandId, String exerciseId, String workspaceInstanceId,
    String executionInstanceId, String baseRevision, List<WorkspaceFileDifferenceResponse> changes) {
    public CommandChangesResponse { changes = List.copyOf(changes); }
}
