package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotStartRequest(String text, boolean feeAcknowledged, List<String> skillIds, String conversationId) {
    public CopilotStartRequest { skillIds = skillIds == null ? List.of() : List.copyOf(skillIds); }
    public CopilotStartRequest(String text, boolean feeAcknowledged, List<String> skillIds) { this(text, feeAcknowledged, skillIds, null); }
    public CopilotStartRequest(String text, boolean feeAcknowledged) { this(text, feeAcknowledged, List.of()); }
    @Override public String toString() { return "CopilotStartRequest[redacted]"; }
}
