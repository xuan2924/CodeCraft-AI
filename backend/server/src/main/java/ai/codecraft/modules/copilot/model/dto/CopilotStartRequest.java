package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotStartRequest(String text, boolean feeAcknowledged, List<String> skillIds, String conversationId, List<CopilotReferenceSelectionDTO> references) {
    public CopilotStartRequest { skillIds = skillIds == null ? List.of() : List.copyOf(skillIds); references = references == null ? List.of() : List.copyOf(references); }
    public CopilotStartRequest(String text, boolean feeAcknowledged, List<String> skillIds, String conversationId) { this(text, feeAcknowledged, skillIds, conversationId, List.of()); }
    public CopilotStartRequest(String text, boolean feeAcknowledged, List<String> skillIds) { this(text, feeAcknowledged, skillIds, null); }
    public CopilotStartRequest(String text, boolean feeAcknowledged) { this(text, feeAcknowledged, List.of()); }
    @Override public String toString() { return "CopilotStartRequest[redacted]"; }
}
