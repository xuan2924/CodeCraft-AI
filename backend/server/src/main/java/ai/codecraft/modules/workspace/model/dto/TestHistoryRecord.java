package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

/** Retention inventory without code, log or report bodies. */
public record TestHistoryRecord(String taskId, String executionInstanceId, String workspaceInstanceId, long revision,
    boolean finalAttempt, boolean quiescent, String state, String cleanup, String copyCleanup,
    boolean pruning, boolean recoveryRequired, Instant finishedAt, Instant cleanupAttemptedAt) {}
