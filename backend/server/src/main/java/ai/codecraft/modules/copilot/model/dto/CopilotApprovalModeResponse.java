package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotApprovalModeResponse(String exerciseId, String mode, String version, List<CopilotApprovalChangeResponse> history) {
    public CopilotApprovalModeResponse { history = List.copyOf(history); }
}
