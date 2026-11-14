package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotTestRunEntity(String toolId, String taskId, String exerciseId, String workspaceInstanceId, String testTaskId,
    long userSequence, long revision, String target, String state, String resultState, Long resultVersion, String resultPayload,
    Instant createdAt, Instant completedAt) {
    @Override public String toString() { return "CopilotTestRunEntity[tool=" + toolId + ", state=" + state + ", content=redacted]"; }
}
