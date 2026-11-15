package ai.codecraft.modules.workspace.model.dto;

/** Exact intent receipt or a read-only proof that this intent cannot have been or become admitted. */
public record WorkspaceRestoreResponse(String exerciseId, String requestKey, WorkspaceRestoreRequest intent,
    WorkspaceOperationResponse operation, String notAcceptedReason) {}
