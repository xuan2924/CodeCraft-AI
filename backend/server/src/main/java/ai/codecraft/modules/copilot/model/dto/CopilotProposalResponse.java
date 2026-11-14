package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotProposalResponse(String id, String sequence, String taskId, String state, String mode, String baseRevision,
    int fileCount, String resultRevision, Boolean changed, String decisionType, String decisionReason, String failureCode,
    Instant createdAt, Instant decidedAt, Instant completedAt) {
    @Override public String toString() { return "CopilotProposalResponse[id=" + id + ", state=" + state + "]"; }
}
