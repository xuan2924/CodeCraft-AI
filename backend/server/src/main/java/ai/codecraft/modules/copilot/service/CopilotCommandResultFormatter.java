package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotCommandRecoveryDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotCommandResultDTO;
import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CopilotCommandResultFormatter {
    private static final Set<String> TERMINAL = Set.of("EXITED", "TIMED_OUT", "STOPPED", "ENVIRONMENT_ERROR", "INTERRUPTED");
    public CopilotCommandResultDTO format(CopilotCommandRecoveryDTO checkpoint, CommandTaskResponse source) {
        var binding = checkpoint.command();
        if (source == null || !binding.commandTaskId().equals(source.id()) || !binding.exerciseId().equals(source.exerciseId())
            || !binding.workspaceInstanceId().equals(source.workspaceInstanceId()) || !Long.toString(binding.baseRevision()).equals(source.revision())
            || !binding.commandText().equals(source.command()) || !binding.workingDirectory().equals(source.directory())
            || !Long.toString(checkpoint.evidence().version()).equals(source.stateVersion())) {
            throw new IllegalStateException("Command result does not match its parent binding");
        }
        if (!source.quiescent() || source.completedAt() == null || !TERMINAL.contains(source.state())
            || !Set.of("SYNCED", "UNCHANGED", "CONFLICT", "REJECTED").contains(source.synchronization())
            || !"SUCCEEDED".equals(source.cleanup()) || !"SUCCEEDED".equals(source.copyCleanup())) {
            throw new IllegalStateException("Command and file changes have not converged");
        }
        var excerpt = CopilotLogProjection.tail(source.logs());
        var result = new CommandTaskResponse(source.id(), source.exerciseId(), source.executionInstanceId(), source.workspaceInstanceId(),
            source.revision(), source.command(), source.directory(), source.state(), source.reason(), source.stopRequested(), true,
            source.synchronization(), source.synchronizationReason(), source.synchronizedRevision(), source.changed(), source.exitCode(),
            excerpt.logs(), source.droppedBytes(), source.logsIncomplete(), source.cleanup(), source.copyCleanup(), source.stateVersion(),
            source.createdAt(), source.completedAt());
        String code = Set.of("CONFLICT", "REJECTED").contains(source.synchronization()) ? "COMMAND_SYNC_FAILED" : "COMMAND_RESULT";
        return new CopilotCommandResultDTO(code, source.reason(), result, excerpt.truncated(), excerpt.omittedBytes());
    }
}
