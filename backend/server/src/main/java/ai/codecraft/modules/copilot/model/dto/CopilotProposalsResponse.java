package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotProposalsResponse(List<CopilotProposalResponse> items, String nextCursor) {
    public CopilotProposalsResponse { items = List.copyOf(items); }
}
