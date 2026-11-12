package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record FinalizationResponse(String operationId, String finalRevision, String verification, String resultTaskId,
    int attempts, int maxAttempts, Instant deadlineAt, Instant endedAt, String status) {}
