package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotProposalPreparedDTO(String workspaceInstanceId, String revision, String batchPayload, String diffPayload, List<CopilotFileDiffDTO> files) {
    public CopilotProposalPreparedDTO { files = List.copyOf(files); }
    @Override public String toString() { return "CopilotProposalPreparedDTO[instance=" + workspaceInstanceId + ", revision=" + revision + ", content=redacted]"; }
}
