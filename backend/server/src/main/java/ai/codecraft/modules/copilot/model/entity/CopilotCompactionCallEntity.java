package ai.codecraft.modules.copilot.model.entity;

import java.time.Instant;

public record CopilotCompactionCallEntity(String id, String compactionId, String exerciseId, int ordinal, String state,
    String connectionId, String modelConfigurationId, long connectionConfigurationVersion, long modelConfigurationVersion,
    String protocol, String modelId, int contextWindow, int outputLimit, long sourceVersion, String sourceSummaryId,
    long throughSequence, String sourceIdsPayload, long estimatedInputTokens, String configurationPayload,
    String responsePayload, Long inputTokens, Long outputTokens, Long totalTokens, boolean usageIncomplete,
    String usagePayload, String errorCode, Instant createdAt, Instant notAfter, Instant completedAt) {
    @Override public String toString() { return "CopilotCompactionCallEntity[id=" + id + ", state=" + state + "]"; }
}
