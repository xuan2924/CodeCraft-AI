package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record InterviewerTaskResponse(String id, String exerciseId, String question, String answer, String state,
    String reason, String platformVersionId, String phase, String revision, Instant createdAt, Instant completedAt) {}
