package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;

public record WorkspaceChangeResponse(String id, String exerciseId, String source, String baseRevision,
    String resultRevision, String taskId, String conversationId, String originChangeId, boolean retained, Instant recordedAt) {}
