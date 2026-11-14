package ai.codecraft.modules.copilot.model.dto;

import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;

public record CopilotTestResultDTO(String code, boolean reused, TestTaskResponse result, boolean logTruncated, String omittedLogBytes) {
    @Override public String toString() { return "CopilotTestResultDTO[code=" + code + ", content=redacted]"; }
}
