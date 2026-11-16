package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.service.ExerciseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopilotReadToolsTest {
    @Test
    void searchesLiteralTextAndMatchesOnlyPublishedPathsWithBoundedResults() {
        var exercises = mock(ExerciseService.class);
        when(exercises.find("session", "exercise")).thenReturn(new ExerciseResponse("exercise", "problem", "instance", "prepare", "ACTIVE", "7", "1",
            List.of("src/Main.java", "src/Other.java"), null, null, "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null, null, null));
        when(exercises.read("session", "exercise", "src/Main.java")).thenReturn(new WorkspaceFileResponse("instance", "7", "src/Main.java", "a.*b\nother"));
        when(exercises.read("session", "exercise", "src/Other.java")).thenReturn(new WorkspaceFileResponse("instance", "7", "src/Other.java", "axxb"));
        var tools = new CopilotReadTools(exercises, new ObjectMapper());
        assertThat(tools.execute("session", "exercise", "glob_files", "{\"pattern\":\"src/*.java\"}").lines()).containsExactly("src/Main.java", "src/Other.java");
        assertThat(tools.execute("session", "exercise", "grep_code", "{\"pattern\":\"a.*b\"}").lines()).containsExactly("src/Main.java:1: a.*b");
        assertThat(tools.execute("session", "exercise", "grep_code", "{\"pattern\":\"a\",\"shell\":true}").code()).isEqualTo("INVALID_ARGUMENTS");
    }

    @Test
    void rejectsMalformedArgumentsMarksTruncationAndNeverMixesRevisions() {
        var exercises = mock(ExerciseService.class);
        var tools = new CopilotReadTools(exercises, new ObjectMapper());
        for (String malformed : List.of("{", "{\"path\":\"a\",\"path\":\"b\"}", "{\"path\":\"a\"}{}", "{\"path\":\"a\",\"start_line\":1.2}", "{\"path\":\"a\",\"end_line\":201}")) {
            assertThat(tools.execute("session", "exercise", "view_file", malformed).code()).isEqualTo("INVALID_ARGUMENTS");
        }
        verify(exercises, never()).find(anyString(), anyString());
        when(exercises.find("session", "exercise")).thenReturn(new ExerciseResponse("exercise", "problem", "instance", "prepare", "ACTIVE", "7", "1",
            List.of("a"), null, null, "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null, null, null));
        when(exercises.read("session", "exercise", "a")).thenReturn(new WorkspaceFileResponse("instance", "7", "a", "大".repeat(30000)));
        var truncated = tools.execute("session", "exercise", "view_file", "{\"path\":\"a\"}");
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.lines()).isEmpty();
        when(exercises.read("session", "exercise", "a")).thenReturn(new WorkspaceFileResponse("instance", "8", "a", "changed"));
        assertThat(tools.execute("session", "exercise", "view_file", "{\"path\":\"a\"}").code()).isEqualTo("REVISION_CONFLICT");
    }

    @Test
    void readsNumberedSavedLinesAndRejectsPathsOutsideThePublishedWorkspace() {
        var exercises = mock(ExerciseService.class);
        when(exercises.find("session", "exercise")).thenReturn(new ExerciseResponse("exercise", "problem", "instance", "prepare", "ACTIVE", "7", "1",
            List.of("src/Main.java"), null, null, "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null, null, null));
        when(exercises.read("session", "exercise", "src/Main.java")).thenReturn(new WorkspaceFileResponse("instance", "7", "src/Main.java", "first\nsecond\nthird"));
        var tools = new CopilotReadTools(exercises, new ObjectMapper());
        var result = tools.execute("session", "exercise", "view_file", "{\"path\":\"src/Main.java\",\"start_line\":2,\"end_line\":2}");
        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.revision()).isEqualTo("7");
        assertThat(result.lines()).containsExactly("src/Main.java:2: second");
        assertThat(tools.execute("session", "exercise", "view_file", "{\"path\":\"../private\"}").code()).isEqualTo("PATH_NOT_ALLOWED");
        verify(exercises, never()).read(anyString(), anyString(), eq("../private"));
    }
}
