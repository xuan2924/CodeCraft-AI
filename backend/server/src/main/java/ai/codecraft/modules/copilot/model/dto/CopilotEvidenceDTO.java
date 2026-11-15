package ai.codecraft.modules.copilot.model.dto;

public record CopilotEvidenceDTO(String id, String kind, long sequence, String payload, String sourceIdsPayload, String previousSummaryId) {
    @Override public String toString() { return "CopilotEvidenceDTO[REDACTED]"; }
}
