package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.copilot.service.CopilotConversationService;
import ai.codecraft.modules.copilot.service.CopilotTaskService;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.ExerciseReportResponse;
import ai.codecraft.modules.workspace.model.dto.ReportEvidenceDTO;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryResponse;
import ai.codecraft.modules.workspace.model.entity.ExerciseReportEntity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportEvidenceCollectorTest {
    private final ExerciseService exercises = mock(ExerciseService.class);
    private final WorkspaceHistoryService history = mock(WorkspaceHistoryService.class);
    private final ExerciseReportStore reports = mock(ExerciseReportStore.class);
    private final ExerciseRequirementsService requirements = mock(ExerciseRequirementsService.class);
    private final ReportEvidenceCollector collector = new ReportEvidenceCollector(exercises, mock(TestTaskStore.class), history, mock(CopilotConversationService.class),
        mock(CopilotTaskService.class), mock(InterviewerStore.class), reports, requirements);
    private ExerciseReportEntity ticket(String evidence) {
        return new ExerciseReportEntity("exercise", "report", "owner", "session", "version", 7, "UNAVAILABLE", null, false, "仅是自述", "operation", 1, "PREPARING", 0, "attempt", null, evidence, null, null, null, null);
    }
    private ExerciseResponse frozen(String revision) {
        var result = mock(ExerciseResponse.class);
        when(result.state()).thenReturn("ENDED"); when(result.revision()).thenReturn(revision); when(result.workspaceInstanceId()).thenReturn("instance");
        when(result.paths()).thenReturn(List.of("Main.java")); return result;
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
    void boundedUtf8SourceAndSelfReviewAreDistinctEvidenceWithExplicitCoverage() {
        var correct = frozen("7");
        when(exercises.find("session", "exercise")).thenReturn(correct);
        var file = mock(WorkspaceFileResponse.class);
        when(file.revision()).thenReturn("7"); when(file.content()).thenReturn("汉".repeat(2000));
        when(exercises.read("session", "exercise", "Main.java")).thenReturn(file);
        when(requirements.published("exercise")).thenReturn("只使用当前阶段的公开需求");
        when(history.recent("session", "exercise", Long.MAX_VALUE)).thenReturn(new WorkspaceHistoryResponse("exercise", List.of(), null));
        var trace = mock(ExerciseReportResponse.class);
        when(reports.view("session", "exercise")).thenReturn(trace);
        when(reports.encode(any())).thenReturn("{}");
        var evidence = collector.collect(ticket(null));
        var source = evidence.stream().filter(value -> value.kind().equals("FILE")).findFirst().orElseThrow();
        assertThat(source.truncated()).isTrue(); assertThat(source.content().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        assertThat(source.content()).doesNotContain("�");
        assertThat(evidence).extracting(ReportEvidenceDTO::kind).contains("SELF_REVIEW", "SCOPE", "REQUIREMENTS", "TIMELINE", "CONTEXT_TRACE");
    }
}
