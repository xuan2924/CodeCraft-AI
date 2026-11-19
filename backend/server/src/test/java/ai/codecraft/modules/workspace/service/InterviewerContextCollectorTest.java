package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.InterviewerDispatchDTO;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryResponse;
import ai.codecraft.modules.workspace.model.entity.InterviewerTaskEntity;
import ai.codecraft.modules.workspace.repository.InterviewerRepository;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class InterviewerContextCollectorTest {
    private final InterviewerRepository tasks = mock(InterviewerRepository.class);
    private final ExerciseService exercises = mock(ExerciseService.class);
    private final WorkspaceHistoryService history = mock(WorkspaceHistoryService.class);
    private final InterviewerContextCollector collector = new InterviewerContextCollector(tasks, exercises, history);
    private InterviewerDispatchDTO dispatch(String mode) {
        return new InterviewerDispatchDTO("task", "exercise", "question", "ACTIVE", "CHALLENGE", 7L, mode, "rounding", 2, "session", null, Instant.MAX);
    }
    @Test
    void latestEightCompletedTurnsAreChronologicalBoundedAndSeparateFromCopilot() {
        var turns = IntStream.range(0, 10).mapToObj(index -> new InterviewerTaskEntity("id" + index, "exercise", "owner", "session", "control", "key",
            "question" + index, "answer" + index + "汉".repeat(3000), "SUCCEEDED", null, "version", "ACTIVE", "BASE", "CLARIFY", "general", 1,
            7L, Instant.EPOCH, Instant.MAX, Instant.EPOCH, null)).toList();
        when(tasks.completedHistory("exercise")).thenReturn(turns);
        when(tasks.latestTest("exercise")).thenReturn("state=FAILED, target=challenge, revision=7");
        String result = collector.collect(dispatch("CLARIFY"));
        assertThat(result).contains("CHALLENGE", "revision: 7", "hint level: 2", "state=FAILED", "[truncated]");
        assertThat(result.indexOf("question7")).isLessThan(result.indexOf("question0"));
        assertThat(result).doesNotContain("question8", "question9");
        assertThat(result.length()).isLessThan(26000);
        verify(exercises, never()).find(anyString(), anyString());
    }
    @Test
    void defenseReadsOnlyBoundedSavedFilesWithoutTransactionAndRejectsRevisionDrift() throws Exception {
        assertThat(InterviewerContextCollector.class.getMethod("collect", InterviewerDispatchDTO.class).getAnnotation(Transactional.class).propagation())
            .isEqualTo(Propagation.NEVER);
        var snapshot = mock(ExerciseResponse.class);
        when(snapshot.revision()).thenReturn("7");
        when(snapshot.problemVersionId()).thenReturn("v1");
        when(tasks.problemLanguage("v1")).thenReturn("JAVA_MAVEN");
        when(snapshot.paths()).thenReturn(List.of("src/main/java/A.java", "src/main/java/B.java", "src/main/java/C.java", "src/main/java/D.java", "src/main/java/E.java"));
        when(exercises.find("session", "exercise")).thenReturn(snapshot);
        for (String path : List.of("src/main/java/A.java", "src/main/java/B.java", "src/main/java/C.java", "src/main/java/D.java")) {
            when(exercises.read("session", "exercise", path)).thenReturn(new WorkspaceFileResponse("instance", "7", path, "saved".repeat(1000)));
        }
        when(history.recent("session", "exercise", Long.MAX_VALUE)).thenReturn(new WorkspaceHistoryResponse("exercise", List.of(), null));
        assertThat(collector.collect(dispatch("DEFENSE"))).contains("Untrusted saved editable source", "src/main/java/A.java", "[truncated]").doesNotContain("src/main/java/E.java");
        verify(exercises, never()).read("session", "exercise", "src/main/java/E.java");
        var change = mock(ai.codecraft.modules.workspace.model.dto.WorkspaceChangeResponse.class);
        when(change.retained()).thenReturn(true); when(change.source()).thenReturn("MANUAL"); when(change.id()).thenReturn("last"); when(change.resultRevision()).thenReturn("7");
        when(history.recent("session", "exercise", Long.MAX_VALUE)).thenReturn(new WorkspaceHistoryResponse("exercise", List.of(change), null));
        var preview = mock(ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryPreviewResponse.class);
        when(preview.changes()).thenReturn(List.of(new ai.codecraft.modules.workspace.model.dto.WorkspaceFileDifferenceResponse("UPDATE", "src/main/java/E.java", "old", "new")));
        when(history.preview("session", "exercise", "last", "7", "DIFF", null)).thenReturn(preview);
        when(exercises.read("session", "exercise", "src/main/java/E.java")).thenReturn(new WorkspaceFileResponse("instance", "7", "src/main/java/E.java", "new"));
        assertThat(collector.collect(dispatch("DEFENSE"))).contains("src/main/java/E.java").doesNotContain("src/main/java/D.java");
        when(change.source()).thenReturn("AI_PATCH");
        assertThat(collector.collect(dispatch("DEFENSE"))).contains("src/main/java/E.java").doesNotContain("src/main/java/D.java");
        when(exercises.read("session", "exercise", "src/main/java/A.java")).thenReturn(new WorkspaceFileResponse("instance", "8", "src/main/java/A.java", "changed"));
        assertThatThrownBy(() -> collector.collect(dispatch("DEFENSE"))).hasMessage("Saved evidence revision changed");
    }
    @Test
    void hintPrioritizesNamedEditableSourceAndKeepsOlderFailureSeparateFromLatestPass() {
        when(tasks.latestTest("exercise")).thenReturn("state=PASSED, revision=7");
        when(tasks.latestFailedTest("exercise", 7)).thenReturn("state=FAILED, revision=5, reason=compilation_failed");
        var snapshot = mock(ExerciseResponse.class);
        when(snapshot.revision()).thenReturn("7");
        when(snapshot.problemVersionId()).thenReturn("v1");
        when(tasks.problemLanguage("v1")).thenReturn("JAVA_MAVEN");
        var paths = List.of("src/main/java/A.java", "src/main/java/B.java", "src/main/java/C.java", "src/main/java/D.java",
            "src/test/java/Rounding.java", "private/Rounding.java");
        when(snapshot.paths()).thenReturn(paths);
        when(exercises.find("session", "exercise")).thenReturn(snapshot);
        when(history.recent("session", "exercise", Long.MAX_VALUE)).thenReturn(new WorkspaceHistoryResponse("exercise", List.of(), null));
        for (String path : paths) when(exercises.read("session", "exercise", path)).thenReturn(new WorkspaceFileResponse("instance", "7", path, "saved"));
        assertThat(collector.collect(dispatch("HINT"))).contains("state=PASSED, revision=7", "state=FAILED, revision=5, reason=compilation_failed",
            "src/test/java/Rounding.java").doesNotContain("private/Rounding.java", "src/main/java/D.java");
    }
    @Test
    void unicodeExcerptDoesNotSplitSurrogatePairs() {
        assertThat(InterviewerContextCollector.excerpt("🙂🙂", 1)).isEqualTo("🙂 [truncated]");
    }
}
