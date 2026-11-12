package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

/** The task ID also serves as the immutable attempt ID. */
public record FinalTaskAuthorityEntity(String taskId, String finalizationId, String freezeOperationId, Instant deadlineAt) {}
