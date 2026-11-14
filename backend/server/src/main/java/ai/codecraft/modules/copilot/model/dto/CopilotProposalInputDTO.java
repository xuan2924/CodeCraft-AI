package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.workspace.model.dto.FileChangeRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CopilotProposalInputDTO(@JsonProperty("expected_revision") String expectedRevision, List<FileChangeRequest> changes) {
    @Override public String toString() { return "CopilotProposalInputDTO[revision=" + expectedRevision + ", content=redacted]"; }
}
