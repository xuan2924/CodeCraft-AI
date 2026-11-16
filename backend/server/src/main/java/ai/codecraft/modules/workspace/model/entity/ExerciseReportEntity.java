package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record ExerciseReportEntity(String exerciseId, String id, String userId, String sessionHash, String platformVersionId,
    long finalRevision, String verification, String finalTestId, boolean reviewSkipped, String reviewText, String operationId,
    long generation, String state, int attempts, String attemptId, Instant deadlineAt, String evidence, String evaluation,
    String reason, Instant createdAt, Instant updatedAt) {
    @Override public String toString() { return "ExerciseReportEntity[REDACTED]"; }
}
