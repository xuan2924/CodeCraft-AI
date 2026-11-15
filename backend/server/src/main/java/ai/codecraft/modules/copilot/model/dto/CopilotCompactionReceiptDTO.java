package ai.codecraft.modules.copilot.model.dto;

public record CopilotCompactionReceiptDTO(String responsePayload, String summaryPayload, String failureCode,
    Long inputTokens, Long outputTokens, Long totalTokens, boolean usageIncomplete, String usagePayload) {
    @Override public String toString() { return "CopilotCompactionReceiptDTO[REDACTED]"; }
}
