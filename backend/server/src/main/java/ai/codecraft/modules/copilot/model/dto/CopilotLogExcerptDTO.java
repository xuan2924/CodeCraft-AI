package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import java.util.List;

public record CopilotLogExcerptDTO(List<TestLogResponse> logs, boolean truncated, String omittedBytes) {}
