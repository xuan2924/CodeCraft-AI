package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;
import java.util.List;

public record ExerciseResponse(String exerciseId, String problemVersionId, String workspaceInstanceId,
                               String preparationOperationId, String state, String revision, String stateVersion,
                               List<String> paths, Instant startedAt, Instant deadlineAt,
                               String reportAvailability, String preparationFailureReason, String endReason, String cleanupState, FinalizationResponse finalization) {}
