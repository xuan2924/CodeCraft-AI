package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record WorkspaceChangeEntity(String id, String exerciseId, String workspaceInstanceId, String source,
    long baseRevision, long resultRevision, String taskId, String conversationId, String originChangeId,
    boolean retained, Instant recordedAt) {}
