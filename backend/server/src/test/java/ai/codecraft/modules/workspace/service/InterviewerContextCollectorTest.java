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
        String result = collector.collect(dispatch("HINT"));
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
        when(snapshot.paths()).thenReturn(List.of("src/main/java/A.java", "src/main/java/B.java", "src/main/java/C.java", "src/main/java/D.java", "src/main/java/E.java"));
        when(exercises.find("session", "exercise")).thenReturn(snapshot);
        for (String path : List.of("src/main/java/A.java", "src/main/java/B.java", "src/main/java/C.java", "src/main/java/D.java")) {
            when(exercises.read("session", "exercise", path)).thenReturn(new WorkspaceFileResponse("instance", "7", path, "saved".repeat(1000)));
        }
        when(history.recent("session", "exercise", Long.MAX_VALUE)).thenReturn(new WorkspaceHistoryResponse("exercise", List.of(), null));
        assertThat(collector.collect(dispatch("DEFENSE"))).contains("Untrusted saved editable source", "src/main/java/A.java", "[truncated]").doesNotContain("src/main/java/E.java");
        verify(exercises, never()).read("session", "exercise", "src/main/java/E.java");
        when(exercises.read("session", "exercise", "src/main/java/A.java")).thenReturn(new WorkspaceFileResponse("instance", "8", "src/main/java/A.java", "changed"));
        assertThatThrownBy(() -> collector.collect(dispatch("DEFENSE"))).hasMessage("Defense revision changed");
    }
    @Test
    void unicodeExcerptDoesNotSplitSurrogatePairs() {
        assertThat(InterviewerContextCollector.excerpt("🙂🙂", 1)).isEqualTo("🙂 [truncated]");
    }
}
