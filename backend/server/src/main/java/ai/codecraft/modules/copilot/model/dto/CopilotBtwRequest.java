package ai.codecraft.modules.copilot.model.dto;

public record CopilotBtwRequest(String text, boolean feeAcknowledged) {
    @Override public String toString() { return "CopilotBtwRequest[redacted]"; }
}
