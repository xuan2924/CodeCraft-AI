package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record WorkspaceHistoryPreviewResponse(String exerciseId, String changeId, String mode, String expectedRevision,
    String targetRevision, String fingerprint, List<WorkspaceFileDifferenceResponse> changes, List<String> conflicts) {
    @Override public String toString() { return "WorkspaceHistoryPreviewResponse[REDACTED]"; }
}
