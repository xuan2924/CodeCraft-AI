package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record ExerciseChallengeEntity(String exerciseId, String operationId, long enteredRevision, String baseTestId, Instant enteredAt) {}
