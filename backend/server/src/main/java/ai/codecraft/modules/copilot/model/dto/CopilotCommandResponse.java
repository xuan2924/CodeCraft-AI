package ai.codecraft.modules.copilot.model.dto;

import java.time.Instant;

public record CopilotCommandResponse(String id, String sequence, String taskId, String exerciseId, String workspaceInstanceId,
    String baseRevision, String command, String directory, String mode, String modeVersion, String state, String commandTaskId,
    String decision, String reason, CopilotCommandResultDTO result, Instant createdAt, Instant decidedAt, Instant completedAt) {}
