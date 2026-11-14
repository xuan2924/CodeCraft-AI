package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotProposalDetailResponse(CopilotProposalResponse proposal, List<CopilotFileDiffDTO> files) {
    public CopilotProposalDetailResponse { files = List.copyOf(files); }
}
