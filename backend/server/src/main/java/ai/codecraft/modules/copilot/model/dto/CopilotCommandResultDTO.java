package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;

public record CopilotCommandResultDTO(String code, String reason, CommandTaskResponse result,
    boolean logTruncated, String omittedLogBytes) {}
