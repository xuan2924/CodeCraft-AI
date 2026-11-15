package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotCommandEntity;

public record CopilotCommandAdmissionDTO(CopilotCommandEntity command, String sessionHash) {
    @Override public String toString() { return "CopilotCommandAdmissionDTO[REDACTED]"; }
}
