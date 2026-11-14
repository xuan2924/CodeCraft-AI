package ai.codecraft.modules.copilot.model.dto;

/** Bounded decision projection; never loads the potentially large original-file evidence. */
public record CopilotProposalDecisionDTO(String id, String taskId, String exerciseId, long baseRevision, String state,
    String batchPayload, String decisionKey, String decisionFingerprint) {
    @Override public String toString() { return "CopilotProposalDecisionDTO[id=" + id + ", content=redacted]"; }
}
