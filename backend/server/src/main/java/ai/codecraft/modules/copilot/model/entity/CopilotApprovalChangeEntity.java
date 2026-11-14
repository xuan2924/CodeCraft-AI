package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotApprovalChangeEntity(String id, String exerciseId, String actorId, long authVersion, String previousMode,
    String mode, long version, String requestKey, String fingerprint, Instant createdAt) {}
