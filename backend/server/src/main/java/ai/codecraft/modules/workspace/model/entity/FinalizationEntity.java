package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

/** Durable lifecycle and budget, distinct from execution capacity and cleanup. */
public record FinalizationEntity(String id, String exerciseId, Long frozenEpoch, Long finalRevision,
    Instant startedAt, Instant deadlineAt, int maxAttempts, int budgetSeconds, int firstRetrySeconds,
    int secondRetrySeconds, int attempts, String currentTaskId, Instant nextAttemptAt,
    String verification, String resultTaskId, Instant endedAt, boolean cancellationPending) {}
