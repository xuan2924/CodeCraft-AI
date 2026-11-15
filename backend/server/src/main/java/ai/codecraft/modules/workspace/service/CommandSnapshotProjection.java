package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;
import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.workspace.v1.CommandSnapshot;
import java.time.Instant;
import java.util.Set;

final class CommandSnapshotProjection {
    private static final Set<String> STATES = Set.of("STARTING", "RUNNING", "STOPPING", "EXITED", "TIMED_OUT", "STOPPED", "ENVIRONMENT_ERROR", "INTERRUPTED");
    private static final Set<String> SYNC = Set.of("PENDING", "SYNCED", "UNCHANGED", "CONFLICT", "REJECTED");
    private static final Set<String> CLEANUP = Set.of("PENDING", "SUCCEEDED", "FAILED");

    private CommandSnapshotProjection() { }

    static CommandTaskResponse project(CommandTaskEntity task, CommandSnapshot command, ProblemVersionEntity expected) {
        var execution = command.getExecution();
        var problem = execution.getProblem();
        if (!command.hasExecution() || !execution.getExerciseId().equals(task.exerciseId()) || !execution.getTaskId().equals(task.id())
            || !execution.getExecutionInstanceId().equals(task.executionInstanceId()) || !execution.getWorkspaceInstanceId().equals(task.workspaceInstanceId())
            || !execution.getTarget().equals("bash") || execution.getRevision() != task.revision() || execution.hasEvidence()
            || !problem.getProblemId().equals(expected.problemId()) || !problem.getVersion().equals(expected.version())
            || !problem.getPackageSha256().equals(expected.packageSha256()) || !problem.getExecutionImageDigest().equals(expected.executionImageDigest())
            || !problem.getPlatform().equals(expected.platform()) || !STATES.contains(execution.getState()) || !SYNC.contains(command.getSynchronization())
            || !CLEANUP.contains(execution.getCleanup()) || !CLEANUP.contains(execution.getCopyCleanup())
            || execution.getStateVersion() < 0 || execution.getDroppedBytes() < 0 || execution.getLogsCount() > 4096
            || execution.getLogsList().stream().anyMatch(log -> !Set.of("stdout", "stderr").contains(log.getChannel()) || log.getData().size() > 16384)
            || execution.getLogsList().stream().mapToLong(log -> log.getData().size()).sum() > 1048576
            || (execution.getQuiescent() && !execution.hasFinishedAtUnixMillis())
            || (execution.hasExitCode() && (execution.getExitCode() < 0 || execution.getExitCode() > 255))
            || (execution.getState().equals("EXITED") && !execution.hasExitCode())
            || (!command.getSynchronization().equals("PENDING") && !execution.getQuiescent())
            || command.hasMutation() != command.getSynchronization().equals("SYNCED")) {
            throw new IllegalStateException("Invalid development command snapshot");
        }
        String revision = null;
        boolean changed = false;
        if (command.hasMutation()) {
            var mutation = command.getMutation();
            if (!mutation.getWorkspaceInstanceId().equals(task.workspaceInstanceId()) || !mutation.getOperationId().equals(task.id())
                || !mutation.getRequestId().equals(task.executionInstanceId()) || !mutation.getFingerprint().matches("[a-f0-9]{64}")
                || mutation.getRevision() < task.revision() || mutation.getRevision() - task.revision() != (mutation.getChanged() ? 1 : 0)) {
                throw new IllegalStateException("Invalid command synchronization receipt");
            }
            revision = Long.toString(mutation.getRevision());
            changed = mutation.getChanged();
        }
        return new CommandTaskResponse(task.id(), task.exerciseId(), task.executionInstanceId(), task.workspaceInstanceId(), Long.toString(task.revision()),
            task.commandText(), task.workingDirectory(), execution.getState(), execution.getReason(), task.stopRequested(), execution.getQuiescent(),
            command.getSynchronization(), command.getSynchronizationReason(), revision, changed, execution.hasExitCode() ? execution.getExitCode() : null,
            execution.getLogsList().stream().map(log -> new TestLogResponse(log.getChannel(), log.getData().toStringUtf8())).toList(),
            Long.toString(execution.getDroppedBytes()), execution.getLogsIncomplete(), execution.getCleanup(), execution.getCopyCleanup(),
            Long.toString(execution.getStateVersion()), task.createdAt(), execution.hasFinishedAtUnixMillis() ? Instant.ofEpochMilli(execution.getFinishedAtUnixMillis()) : null);
    }
}
