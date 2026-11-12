package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record DeletionResponse(String exerciseId, String state, Instant requestedAt, Instant completedAt,
    Instant nextAttemptAt, String failureCode, boolean interventionRequired) {}
