package ai.codecraft.modules.copilot.model.dto;

/** Immutable bounded evidence for validation/measurement outside the admission transaction. */
public record CopilotAutoApprovalDTO(String proposalId, String taskId, String exerciseId, String sessionHash,
    long modeVersion, long baseRevision, String batchPayload) {
    @Override public String toString() { return "CopilotAutoApprovalDTO[proposalId=" + proposalId + ", content=redacted]"; }
}
