package ai.codecraft.modules.workspace.model.dto;

public record WorkspaceFileDifferenceResponse(String kind, String path, String previousContent, String content) {
    @Override public String toString() { return "WorkspaceFileDifferenceResponse[REDACTED]"; }
}
