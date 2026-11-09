package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record ExerciseEntity(String id, String userId, String problemVersionId, String workspaceInstanceId,
                             String preparationOperationId, String state, Long revision, String paths,
                             long stateVersion, Instant createdAt, Instant startedAt, Instant deadlineAt,
                             String preparationFailureReason, String endReason, String cleanupState) {}
