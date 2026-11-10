package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;
import java.util.List;

public record TestTaskResponse(String taskId, String exerciseId, String executionInstanceId,
    String workspaceInstanceId, String revision, String target, String state, String reason,
    Instant startedAt, Instant finishedAt, Integer exitCode, boolean quiescent, String cleanup,
    String copyCleanup, String droppedBytes, boolean logsIncomplete, List<TestLogResponse> logs,
    TestProblemResponse problem, TestEvidenceResponse evidence) {}
