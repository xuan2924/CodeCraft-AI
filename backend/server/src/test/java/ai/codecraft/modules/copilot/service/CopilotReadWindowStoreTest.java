package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTurnEntity;
import ai.codecraft.modules.copilot.repository.CopilotSteerRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopilotReadWindowStoreTest {
    private final CopilotTaskRepository tasks = mock(CopilotTaskRepository.class);
    private final CopilotSteerRepository steers = mock(CopilotSteerRepository.class);
    private final WorkspaceAiOperationService operations = mock(WorkspaceAiOperationService.class);
    private final CopilotProperties limits = mock(CopilotProperties.class);
    private final CopilotExecutionStore store = new CopilotExecutionStore(tasks, mock(IdentityTransactionService.class), null, null, operations,
        limits, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), null, null, null, null, steers, null, null, null, null, null, null, mock(CopilotActivitySignals.class));
    private final List<ToolExecutionRequest> requests = List.of(request("old"), request("read"), request("grep"));
    private static ToolExecutionRequest request(String name) {
        return ToolExecutionRequest.builder().id(name).name(name).arguments("{}").build();
    }
    private void setup() {
        when(tasks.find("task")).thenReturn(new CopilotTaskEntity("task", "exercise", "user", "session", "key", "fingerprint", "RUNNING", null,
            false, "worker", Instant.MAX, Instant.EPOCH, null, "conversation", "MAIN"));
        when(tasks.latestTurn("task")).thenReturn(new CopilotTurnEntity("turn", "task", "exercise", 1, "TOOLS", null, null, 0, 0, null, null, 0, "assistant", 1, Instant.EPOCH, Instant.MAX, null, null));
        when(tasks.messageById("assistant")).thenReturn(new CopilotMessageEntity(0, "assistant", "task", "exercise", "ASSISTANT", ChatMessageSerializer.messageToJson(AiMessage.from(requests)), Instant.EPOCH));
        when(limits.workerLease()).thenReturn(Duration.ofSeconds(30));
    }
    @Test void admissionUsesDurablePositionsWithoutAdvancingAndOutOfOrderCompletionIsRejected() {
        setup();
        var admitted = store.beginReadTools("task", "worker", "turn", requests.subList(1, 3), null);
        assertThat(admitted).extracting(tool -> tool.position()).containsExactly(1, 2);
        assertThat(admitted).extracting(tool -> tool.callId()).containsExactly("read", "grep");
        verify(tasks, never()).advanceTool(anyString(), anyInt(), anyBoolean());
        assertThat(store.completeTool("task", "worker", admitted.getLast(), "{}", true)).isFalse();
        verify(tasks, never()).completeTool(anyString(), anyString(), anyString(), any());
        // An already admitted window must not be replayed after a partial restart.
        when(tasks.toolAt("turn", 1)).thenReturn(admitted.getFirst());
        assertThat(store.beginReadTools("task", "worker", "turn", requests.subList(1, 3), null)).isEmpty();
    }
    @Test void steerAndLostOwnershipPreventReadAdmission() {
        setup();
        when(steers.unstaged("task")).thenReturn(true);
        assertThat(store.beginReadTools("task", "worker", "turn", requests.subList(1, 3), null)).isEmpty();
        assertThat(store.beginReadTools("task", "different-worker", "turn", requests.subList(1, 3), null)).isEmpty();
        verify(tasks, never()).tool(any());
    }
    @Test void rejectsSideEffectsAndOversizedWindowsBeforeAnyReservation() {
        assertThatThrownBy(() -> store.beginReadTools("task", "worker", "turn", List.of(request("bash")), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.beginReadTools("task", "worker", "turn", java.util.Collections.nCopies(5, request("read")), null)).isInstanceOf(IllegalArgumentException.class);
        verify(tasks, never()).tool(any());
    }
}
