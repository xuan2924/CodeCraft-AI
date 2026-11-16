package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record ExercisePauseResponse(String pauseOperationId, String resumeOperationId, Long remainingMillis, Instant confirmedAt) {}
