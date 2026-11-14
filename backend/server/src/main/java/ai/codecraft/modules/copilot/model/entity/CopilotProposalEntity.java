package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotProposalEntity(String id, long sequence, String taskId, String toolId, String turnId, String exerciseId, String userId,
    String workspaceInstanceId, long baseRevision, String state, String mode, int fileCount, String batchPayload, String diffPayload,
    String fingerprint, String operationId, String decisionKey, String decisionFingerprint, String decisionType, String decisionActor,
    String decisionReason, Long resultRevision, Boolean changed, String failureCode, Instant createdAt, Instant decidedAt, Instant completedAt) {
    @Override public String toString() { return "CopilotProposalEntity[id=" + id + ", state=" + state + ", content=redacted]"; }
}
