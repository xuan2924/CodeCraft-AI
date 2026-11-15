package ai.codecraft.modules.workspace.model.entity;

public record WorkspaceRestoreEntity(String operationId, String exerciseId, String changeId, String mode,
    String requestFingerprint, String previewFingerprint, long expectedRevision, Long targetRevision) {}
