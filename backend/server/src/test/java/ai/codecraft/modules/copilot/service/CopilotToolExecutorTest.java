package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.copilot.model.dto.CopilotProposalPreparedDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotToolExecutionDTO;
import ai.codecraft.modules.copilot.model.dto.ToolOutcome;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class CopilotToolExecutorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CopilotTaskRepository tasks = mock(CopilotTaskRepository.class);
    private final CopilotProposalPreparation preparation = mock(CopilotProposalPreparation.class);
    private final CopilotProposalStore proposals = mock(CopilotProposalStore.class);
    private final CopilotTestTools tests = mock(CopilotTestTools.class);
    private final CopilotCommandService commands = mock(CopilotCommandService.class);
    private final CopilotToolExecutor executor = new CopilotToolExecutor(mock(CopilotReadTools.class), preparation, proposals, json,
        tests, mock(CopilotEvidenceTools.class), mock(CopilotSkillTools.class), commands, mock(CopilotWebTools.class),
        mock(CopilotReferenceService.class), mock(CopilotDelegationService.class), tasks);

    @Test void suppliesOnlyTheTasksPersistedBaselineAndRejectsExplicitMismatch() throws Exception {
        var task = task("MAIN");
        when(tasks.planningRevision("task")).thenReturn("7");
        when(tests.execute(eq(task), eq("worker"), any())).thenReturn(new CopilotToolExecutionDTO(true, null));
        assertThat(executor.execute(task, "worker", tool("run_tests", "{\"target\":\"base\"}"))).isInstanceOf(ToolOutcome.Suspended.class);
        var supplied = ArgumentCaptor.forClass(CopilotToolEntity.class);
        verify(tests).execute(eq(task), eq("worker"), supplied.capture());
        assertThat(json.readTree(supplied.getValue().arguments()).path("expected_revision").asText()).isEqualTo("7");
        var stale = executor.execute(task, "worker", tool("bash", "{\"expected_revision\":\"8\",\"command\":\"pwd\",\"directory\":\".\"}"));
        assertThat(((ToolOutcome.Completed) stale).outcomeCode()).isEqualTo(ToolOutcome.Code.CONFLICT);
        verify(commands, never()).execute(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "write", "run_tests", "bash"})
    void legacyExplicitRevisionMismatchCannotReachAnyEffect(String name) throws Exception {
        when(tasks.planningRevision("task")).thenReturn("7");
        for (String revision : List.of("\"8\"", "null", "7")) {
            var outcome = (ToolOutcome.Completed) executor.execute(task("MAIN"), "worker", tool(name, "{\"expected_revision\":" + revision + "}"));
            assertThat(json.readTree(outcome.body()).path("code").asText()).isEqualTo("REVISION_CONFLICT");
        }
        verify(preparation, never()).prepare(any(), any(), any(), any());
        verify(tests, never()).execute(any(), any(), any());
        verify(commands, never()).execute(any(), any(), any());
    }

    @Test void modernWriteUsesTheUnchangedApprovalPathAndBtwCannotReachIt() throws Exception {
        when(tasks.planningRevision("task")).thenReturn("7");
        var prepared = new CopilotProposalPreparedDTO("instance", "7", "{}", "[]", List.of());
        when(preparation.prepare(eq("session"), eq("exercise"), eq("write"), any())).thenReturn(prepared);
        when(proposals.register("task", "worker", "tool", prepared)).thenReturn(true);
        assertThat(executor.execute(task("MAIN"), "worker", tool("write", "{\"path\":\"a\",\"content\":\"b\"}"))).isInstanceOf(ToolOutcome.Suspended.class);
        var denied = executor.execute(task("BTW"), "worker", tool("edit", "{\"files\":[]}"));
        assertThat(((ToolOutcome.Completed) denied).outcomeCode()).isEqualTo(ToolOutcome.Code.DENIED);
        verify(preparation, never()).prepare(any(), any(), eq("edit"), any());
    }

    @Test void diagnosticEditFailureCompletesRatherThanCrashesTheTask() throws Exception {
        when(tasks.planningRevision("task")).thenReturn("7");
        when(preparation.prepare(any(), any(), eq("edit"), any())).thenThrow(new CopilotEditException("EDIT_AMBIGUOUS_MATCH", "a", 42));
        var outcome = (ToolOutcome.Completed) executor.execute(task("MAIN"), "worker", tool("edit", "{\"files\":[]}"));
        assertThat(outcome.outcomeCode()).isEqualTo(ToolOutcome.Code.INVALID_ARGUMENTS);
        assertThat(json.readTree(outcome.payload()).path("lineCount").asInt()).isEqualTo(42);
        verify(proposals, never()).register(any(), any(), any(), any());
    }

    private CopilotTaskEntity task(String lane) {
        return new CopilotTaskEntity("task", "exercise", "user", "session", "key", "fingerprint", "RUNNING", null,
            false, "worker", Instant.MAX, Instant.EPOCH, null, "conversation", lane);
    }
    private CopilotToolEntity tool(String name, String input) {
        return new CopilotToolEntity("tool", "turn", "task", "exercise", 0, "call", name, input, "RUNNING", null, Instant.EPOCH, null);
    }
}
