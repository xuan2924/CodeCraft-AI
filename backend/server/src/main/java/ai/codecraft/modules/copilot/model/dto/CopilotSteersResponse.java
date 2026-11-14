package ai.codecraft.modules.copilot.model.dto;

import java.util.List;

public record CopilotSteersResponse(List<CopilotSteerResponse> items, String nextCursor) {}
