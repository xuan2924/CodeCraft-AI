package ai.codecraft.modules.copilot.model.dto;

public record CopilotStartRequest(String text, boolean feeAcknowledged) {
    @Override public String toString() { return "CopilotStartRequest[redacted]"; }
}
