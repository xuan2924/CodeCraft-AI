package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotTestRunEntity;
import ai.codecraft.modules.workspace.model.dto.TestResultEvidenceDTO;

public record CopilotTestRecoveryDTO(CopilotTestRunEntity binding, TestResultEvidenceDTO evidence) {
    @Override public String toString() { return "CopilotTestRecoveryDTO[content=redacted]"; }
}
