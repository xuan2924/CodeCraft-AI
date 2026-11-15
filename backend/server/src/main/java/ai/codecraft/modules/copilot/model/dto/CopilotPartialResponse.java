package ai.codecraft.modules.copilot.model.dto;

/** A preview is never a confirmed assistant message or an executable tool request. */
public record CopilotPartialResponse(String turnId, String sequence, String text, boolean truncated, String thinking, boolean thinkingTruncated) {
    @Override public String toString() { return "CopilotPartialResponse[turn=" + turnId + ", payload=redacted]"; }
}
