package ai.codecraft.modules.modelaccess.model.dto;

/** Decimal strings avoid JavaScript precision loss. A null sum means no reported counters, not zero. */
public record ModelUsageTotalsResponse(String purpose, String calls, String completeCalls, String inputTokens,
    String ordinaryInputTokens, String cacheReadTokens, String cacheWriteTokens, String outputTokens,
    String totalTokens, String pricedCalls) { }
