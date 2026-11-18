package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record ExercisePhaseDTO(String id, String state, Instant deadlineAt) {
    public boolean ended(Instant now) {
        return switch (state) {
            case "ACTIVE", "PAUSING" -> deadlineAt == null || !now.isBefore(deadlineAt);
            case "PAUSED", "RESUMING" -> false;
            default -> true;
        };
    }
}
