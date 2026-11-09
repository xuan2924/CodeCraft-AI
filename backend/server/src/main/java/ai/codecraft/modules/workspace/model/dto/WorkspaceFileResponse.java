package ai.codecraft.modules.workspace.model.dto;

public record WorkspaceFileResponse(String workspaceInstanceId, String revision, String path, String content) {}
