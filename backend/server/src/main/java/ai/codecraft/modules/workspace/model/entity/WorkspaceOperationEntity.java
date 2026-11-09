package ai.codecraft.modules.workspace.model.entity;

import java.time.Instant;

public record WorkspaceOperationEntity(String id, String userId, String exerciseId, String workspaceInstanceId,
                                       String kind, String target, String requestKey, String fingerprint,
                                       String state, Instant createdAt) {}
