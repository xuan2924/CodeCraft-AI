package ai.codecraft.modules.workspace.model.dto;

import java.util.List;

public record TestHistoryResponse(List<TestTaskResponse> tasks, TestHistoryRetentionResponse retention) {}
