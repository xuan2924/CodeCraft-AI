package ai.codecraft.modules.workspace.model.dto;

public record TestHistoryRetentionResponse(int retainedCompletedLimit, long prunedCount, long cleanupPendingCount) {}
