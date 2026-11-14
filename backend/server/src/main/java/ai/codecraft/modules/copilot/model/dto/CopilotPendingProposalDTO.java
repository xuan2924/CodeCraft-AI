package ai.codecraft.modules.copilot.model.dto;

/** Recovery reads only identity/status, not multi-file source bodies on every maintenance tick. */
public record CopilotPendingProposalDTO(String id, String taskId, String userId, String toolId, String turnId, String exerciseId,
    String workspaceInstanceId, long baseRevision, String state, String operationId, String mode, long modeVersion) {}
