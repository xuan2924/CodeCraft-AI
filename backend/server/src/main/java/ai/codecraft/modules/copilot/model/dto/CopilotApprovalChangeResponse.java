package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotApprovalChangeResponse(String exerciseId, String id, String previousMode, String mode, String version, String actorId, Instant createdAt) {}
