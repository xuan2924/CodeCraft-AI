package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record DeletionEntity(String exerciseId, String userId, String workspaceInstanceId, String state,
    Instant requestedAt, Instant completedAt, int attempts, Instant nextAttemptAt, Instant firstFailureAt, String failureCode) {}
