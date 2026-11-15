package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotTestResultDTO;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Bounded model projection, not an execution authority or a replacement for the original test record. */
@Component
public class CopilotTestResultFormatter {
    private static final Set<String> TERMINAL = Set.of("PASSED", "FAILED", "TIMED_OUT", "STOPPED", "ENVIRONMENT_ERROR", "INTERRUPTED");

    public CopilotTestResultDTO format(TestTaskResponse source, boolean reused) {
        if (source == null || !source.quiescent() || source.finishedAt() == null || source.state() == null || !TERMINAL.contains(source.state())) {
            throw new IllegalStateException("Test execution is not confirmed and quiescent");
        }
        var excerpt = CopilotLogProjection.tail(source.logs());
        var result = new TestTaskResponse(source.taskId(), source.exerciseId(), source.executionInstanceId(), source.workspaceInstanceId(), source.revision(),
            source.target(), source.state(), source.reason(), source.startedAt(), source.finishedAt(), source.exitCode(), true, source.cleanup(), source.copyCleanup(),
            source.droppedBytes(), source.logsIncomplete(), excerpt.logs(), source.problem(), source.evidence());
        return new CopilotTestResultDTO("TEST_RESULT", reused, result, excerpt.truncated(), excerpt.omittedBytes());
    }
}
