package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record ExerciseHistoryResponse(
    String exerciseId,
    String problemTitle,
    String problemVersion,
    String finalVerification,
    Instant endedAt,
    Instant retainUntil,
    String deletionState) {}
