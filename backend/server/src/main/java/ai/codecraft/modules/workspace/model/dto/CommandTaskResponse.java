package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;
import java.util.List;

public record CommandTaskResponse(String id, String exerciseId, String executionInstanceId, String workspaceInstanceId,
    String revision, String command, String directory, String state, String reason, boolean stopRequested, boolean quiescent,
    String synchronization, String synchronizationReason, String synchronizedRevision, boolean changed, Integer exitCode,
    List<TestLogResponse> logs, String droppedBytes, boolean logsIncomplete, String cleanup, String copyCleanup,
    String stateVersion, Instant createdAt, Instant completedAt) {
    public CommandTaskResponse { logs = List.copyOf(logs); }
    @Override public String toString() { return "CommandTaskResponse[id=" + id + ", state=" + state + "]"; }
}
