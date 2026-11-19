package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.copilot.model.dto.CopilotConversationResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotMessageResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotMessagesResponse;
import ai.codecraft.modules.copilot.service.CopilotConversationService;
import ai.codecraft.modules.copilot.service.CopilotTaskService;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.ExerciseReportResponse;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryResponse;
import ai.codecraft.modules.workspace.model.entity.ExerciseReportEntity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import ai.codecraft.modules.workspace.model.dto.ReportTimelineItemDTO;
import org.junit.jupiter.api.Test;

class ReportEvidenceCollectorTest {
    private final ExerciseService exercises = mock(ExerciseService.class);
    private final WorkspaceHistoryService history = mock(WorkspaceHistoryService.class);
    private final ExerciseReportStore reports = mock(ExerciseReportStore.class);
    private final ExerciseRequirementsService requirements = mock(ExerciseRequirementsService.class);
    private final CopilotConversationService conversations = mock(CopilotConversationService.class);
    private final CopilotTaskService copilot = mock(CopilotTaskService.class);
    private final ReportEvidenceCollector collector = new ReportEvidenceCollector(exercises, mock(TestTaskStore.class), history, conversations,
        copilot, mock(InterviewerStore.class), reports, requirements);
    private ExerciseReportEntity ticket(String evidence) {
        return new ExerciseReportEntity("exercise", "report", "owner", "session", "version", 7, "UNAVAILABLE", null, false, "仅是自述", "operation", 1, "PREPARING", 0, "attempt", null, evidence, null, null, null, null);
    }
    private ExerciseResponse frozen(String revision) {
        var result = mock(ExerciseResponse.class);
        when(result.state()).thenReturn("ENDED"); when(result.revision()).thenReturn(revision); when(result.workspaceInstanceId()).thenReturn("instance");
        when(result.paths()).thenReturn(List.of("AGENTS.md", "src/main/java/guide.md", "src/main/java/Main.java")); return result;
    }
    @Test
    void frozenSnapshotIsReusedWithoutReadingCurrentFilesOrPrivateConversations() {
        var ticket = ticket("[]");
        when(reports.evidence(ticket)).thenReturn(List.of(new ReportEvidenceDTO("file", "FILE", "Main.java", "frozen", "old content", false)));
        assertThat(collector.collect(ticket)).extracting(ReportEvidenceDTO::content).containsExactly("old content");
        verify(exercises, never()).find(anyString(), anyString());
    }
    @Test
    void revisionMismatchNeverCollectsMutableSource() {
        var wrong = frozen("8");
        when(exercises.find("session", "exercise")).thenReturn(wrong);
        assertThatThrownBy(() -> collector.collect(ticket(null))).hasMessage("Frozen evidence identity changed");
        verify(exercises, never()).read(anyString(), anyString(), anyString());
    }
    @Test
    void longExerciseKeepsOpeningLateFixTestChangesAndFinalValidationWithinBudget() {
        var correct = frozen("7");
        var paths = new ArrayList<>(IntStream.range(0, 24).mapToObj(i -> "src/main/java/Main" + i + ".java").toList());
        paths.add("src/test/java/BoundaryTest.java");
        when(correct.paths()).thenReturn(paths);
        when(exercises.find("session", "exercise")).thenReturn(correct);
        when(exercises.read(anyString(), anyString(), anyString())).thenReturn(new WorkspaceFileResponse("instance", "7", "path", "saved"));
        when(history.recent("session", "exercise", Long.MAX_VALUE)).thenReturn(new WorkspaceHistoryResponse("exercise", List.of(), null));
        var trace = mock(ExerciseReportResponse.class);
        var timeline = IntStream.range(0, 120).mapToObj(i -> new ReportTimelineItemDTO(Instant.EPOCH.plusSeconds(i),
            i == 91 ? "WORKSPACE_CHANGED" : List.of(17, 42, 53, 108).contains(i) ? "TEST_COMPLETED" : "CLI_PROXY", "event-" + i + ":",
            i == 42 ? "PUBLIC · FAILED" : i == 17 || i == 53 ? "PUBLIC · PASSED" : "汉".repeat(60), null, "id" + i)).toList();
        when(trace.timeline()).thenReturn(timeline);
        when(reports.view("session", "exercise")).thenReturn(trace);
        when(reports.encode(any())).thenAnswer(invocation -> String.valueOf((Object) invocation.getArgument(0)));
        when(conversations.list("session", "exercise")).thenReturn(List.of(new CopilotConversationResponse("lane", "exercise", true, Instant.EPOCH)));
        for (long cursor : List.of(0L, -1L)) {
            int start = cursor == 0 ? 1 : 301;
            var page = IntStream.range(start, start + 100).mapToObj(i -> new CopilotMessageResponse("" + i, "id" + i, "task", "USER", "message " + i, null)).toList();
            when(copilot.messagesInConversation("session", "exercise", cursor, "lane")).thenReturn(new CopilotMessagesResponse(page, null));
        }
        var evidence = collector.collect(ticket(null));
        var events = evidence.stream().filter(item -> item.kind().equals("TIMELINE")).findFirst().orElseThrow();
        assertThat(events.truncated()).isFalse();
        assertThat(events.content()).contains("event-0:", "event-17:", "event-42:", "event-53:", "event-91:", "event-108:", "event-119:");
        assertThat(evidence.stream().filter(item -> item.kind().equals("FILE"))).hasSize(16);
        assertThat(evidence).extracting(ReportEvidenceDTO::source).contains("src/test/java/BoundaryTest.java");
        assertThat(evidence.stream().filter(item -> item.kind().equals("COPILOT_MESSAGE")).map(ReportEvidenceDTO::content))
            .hasSize(6).containsSubsequence("message 1", "message 398", "message 399", "message 400");
    }
    @Test
    void boundedUtf8SourceAndSelfReviewAreDistinctEvidenceWithExplicitCoverage() {
        var correct = frozen("7");
        when(exercises.find("session", "exercise")).thenReturn(correct);
        var file = mock(WorkspaceFileResponse.class);
        when(file.revision()).thenReturn("7"); when(file.content()).thenReturn("汉".repeat(2000));
        when(exercises.read("session", "exercise", "src/main/java/Main.java")).thenReturn(file);
        when(requirements.published("exercise")).thenReturn("只使用当前阶段的公开需求");
        when(history.recent("session", "exercise", Long.MAX_VALUE)).thenReturn(new WorkspaceHistoryResponse("exercise", List.of(), null));
        var trace = mock(ExerciseReportResponse.class);
        when(reports.view("session", "exercise")).thenReturn(trace);
        when(reports.encode(any())).thenReturn("{}");
        var evidence = collector.collect(ticket(null));
        verify(exercises, never()).read("session", "exercise", "src/main/java/guide.md");
        verify(exercises, never()).read("session", "exercise", "AGENTS.md");
        var source = evidence.stream().filter(value -> value.kind().equals("FILE")).findFirst().orElseThrow();
        assertThat(source.truncated()).isTrue(); assertThat(source.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        assertThat(source.content()).doesNotContain("�");
        assertThat(evidence).extracting(ReportEvidenceDTO::kind).contains("SELF_REVIEW", "SCOPE", "REQUIREMENTS", "TIMELINE", "CONTEXT_TRACE");
    }
}
