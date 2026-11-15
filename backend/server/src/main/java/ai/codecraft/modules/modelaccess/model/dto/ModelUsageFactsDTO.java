package ai.codecraft.modules.modelaccess.model.dto;

/** Provider-reported counters normalized to disjoint input categories; null is never a reported zero. */
public record ModelUsageFactsDTO(Long inputTokens, Long ordinaryInputTokens, Long cacheReadTokens, Long cacheWriteTokens,
    Long outputTokens, Long totalTokens, boolean complete, String reason) { }
