package ai.codecraft.modules.copilot.model.dto;

/** Validated text and protocol representation, encoded before entering a transaction. */
public record CopilotSteerPreparedDTO(String text, String payload, String fingerprint) {
    @Override public String toString() { return "CopilotSteerPreparedDTO[redacted]"; }
}
