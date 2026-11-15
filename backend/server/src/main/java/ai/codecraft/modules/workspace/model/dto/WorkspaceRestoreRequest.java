package ai.codecraft.modules.workspace.model.dto;

public record WorkspaceRestoreRequest(String changeId, String mode, String expectedRevision, String targetRevision,
    String previewFingerprint) {}
