package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record ExerciseHistoryResponse(String exerciseId, Instant endedAt, Instant retainUntil, String deletionState) {}
