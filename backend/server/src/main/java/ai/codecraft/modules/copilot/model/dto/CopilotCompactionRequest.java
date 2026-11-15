package ai.codecraft.modules.copilot.model.dto;

public record CopilotCompactionRequest(boolean feeAcknowledged, String conversationId) {
    public CopilotCompactionRequest(boolean feeAcknowledged) { this(feeAcknowledged, null); }
}
