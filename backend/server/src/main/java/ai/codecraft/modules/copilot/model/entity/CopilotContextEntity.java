package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotContextEntity(String exerciseId, long version, String summaryId, long throughSequence, Instant updatedAt) {}
