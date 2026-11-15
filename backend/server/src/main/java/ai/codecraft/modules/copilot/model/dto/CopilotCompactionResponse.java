package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotCompactionResponse(String id, String sequence, String exerciseId, String trigger, String state, String reason,
    int attempts, boolean stopRequested, String sourceVersion, String throughSequence, Instant createdAt, Instant completedAt) {}
