package ai.codecraft.modules.copilot.model.dto;

public record CopilotProposalEvidenceDTO(CopilotProposalResponse proposal, String diffPayload) {
    @Override public String toString() { return "CopilotProposalEvidenceDTO[content=redacted]"; }
}
