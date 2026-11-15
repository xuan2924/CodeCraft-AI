package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotCommandsResponse(List<CopilotCommandResponse> commands, String nextBefore) {}
