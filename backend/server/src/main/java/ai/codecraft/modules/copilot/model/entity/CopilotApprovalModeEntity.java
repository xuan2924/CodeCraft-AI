package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotApprovalModeEntity(String exerciseId, String mode, long version, Instant updatedAt) {}
