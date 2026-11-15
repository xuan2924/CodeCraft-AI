package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotCommandEntity(String id, long sequence, String taskId, String exerciseId, String userId,
    String workspaceInstanceId, long baseRevision, long userSequence, String commandText, String workingDirectory,
    String fingerprint, String mode, long modeVersion, String state, String commandTaskId, String decisionKey,
    String decisionFingerprint, String decision, String decisionActor, Long actorAuthVersion, String reason,
    String resultPayload, Instant createdAt, Instant decidedAt, Instant completedAt) {
    @Override public String toString() { return "CopilotCommandEntity[id=" + id + ", state=" + state + "]"; }
}
