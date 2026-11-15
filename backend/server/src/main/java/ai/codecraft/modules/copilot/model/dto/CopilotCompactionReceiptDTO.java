package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.modelaccess.model.dto.ModelUsageFactsDTO;

public record CopilotCompactionReceiptDTO(String responsePayload, String summaryPayload, String failureCode,
    Long inputTokens, Long outputTokens, Long totalTokens, boolean usageIncomplete, String usagePayload, ModelUsageFactsDTO usageFacts) {
    @Override public String toString() { return "CopilotCompactionReceiptDTO[REDACTED]"; }
}
