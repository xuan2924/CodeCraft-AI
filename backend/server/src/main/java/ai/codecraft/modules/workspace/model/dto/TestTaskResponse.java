package ai.codecraft.modules.workspace.model.dto;

import java.time.Instant;
import java.util.List;

public record TestTaskResponse(String taskId, String exerciseId, String executionInstanceId,
    String workspaceInstanceId, String revision, String target, String state, String reason,
    Instant startedAt, Instant finishedAt, Integer exitCode, boolean quiescent, String cleanup,
    String copyCleanup, String droppedBytes, boolean logsIncomplete, List<TestLogResponse> logs,
    TestProblemResponse problem, TestEvidenceResponse evidence, String streamId, String cursor, String firstRetainedSequence, String sequence) {
    public TestTaskResponse(String taskId, String exerciseId, String executionInstanceId,
        String workspaceInstanceId, String revision, String target, String state, String reason,
        Instant startedAt, Instant finishedAt, Integer exitCode, boolean quiescent, String cleanup,
        String copyCleanup, String droppedBytes, boolean logsIncomplete, List<TestLogResponse> logs,
        TestProblemResponse problem, TestEvidenceResponse evidence) {
        this(taskId, exerciseId, executionInstanceId, workspaceInstanceId, revision, target, state, reason,
            startedAt, finishedAt, exitCode, quiescent, cleanup, copyCleanup, droppedBytes, logsIncomplete,
            logs, problem, evidence, null, null, null, null);
    }
    public TestTaskResponse withStream(String stream, String highWater, String first, String last) {
        return new TestTaskResponse(taskId, exerciseId, executionInstanceId, workspaceInstanceId, revision, target,
            state, reason, startedAt, finishedAt, exitCode, quiescent, cleanup, copyCleanup, droppedBytes,
            logsIncomplete, logs, problem, evidence, stream, highWater, first, last);
    }
}
