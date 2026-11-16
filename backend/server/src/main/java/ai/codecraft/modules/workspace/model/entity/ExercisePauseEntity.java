package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record ExercisePauseEntity(String exerciseId, String pauseOperationId, String resumeOperationId,
                                  Long remainingMillis, Instant confirmedAt) {}
