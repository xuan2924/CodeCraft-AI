package ai.codecraft.modules.copilot.model.dto;

public record CopilotProposalDecisionRequest(String decision, String reason) {
    @Override public String toString() { return "CopilotProposalDecisionRequest[decision=" + decision + ", reason=redacted]"; }
}
