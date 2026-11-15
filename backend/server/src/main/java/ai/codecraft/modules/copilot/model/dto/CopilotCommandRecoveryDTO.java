package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.copilot.model.entity.CopilotCommandEntity;
import ai.codecraft.modules.workspace.model.dto.CommandEvidenceDTO;

public record CopilotCommandRecoveryDTO(CopilotCommandEntity command, CommandEvidenceDTO evidence) {}
