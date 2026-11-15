package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotContextTicketDTO;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.ExerciseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class CopilotProjectInstructionsTest {
    private final ExerciseService exercises = mock(ExerciseService.class);
    private final CopilotTaskRepository tasks = mock(CopilotTaskRepository.class);
    private final CopilotProjectInstructions instructions = new CopilotProjectInstructions(exercises, tasks, new ObjectMapper());
    private CopilotContextTicketDTO ticket(String task) {
        return new CopilotContextTicketDTO(task, "exercise", "session", "worker", 0, null, 0, 0, null, List.of(), 0, "conversation");
    }
    private void snapshot(String revision, List<String> paths) {
        when(exercises.find("session", "exercise")).thenReturn(new ExerciseResponse("exercise", "problem", "instance", "prepare", "ACTIVE", revision, "1",
            paths, null, null, "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null));
    }
    private void file(String path, String revision, String text) {
        when(exercises.read("session", "exercise", path)).thenReturn(new WorkspaceFileResponse("instance", revision, path, text));
    }
    @Test void loadsOnlyRootAndAncestorsOfThisTasksReadsInHierarchyOrder() throws Exception {
        snapshot("7", List.of("AGENTS.md", "src/AGENTS.md", "src/main/AGENTS.md", "src/main/Main.java", "other/AGENTS.md"));
        when(tasks.instructionPaths("task")).thenReturn(List.of("{\"path\":\"src/main/Main.java\"}", "null", "{"));
        file("AGENTS.md", "7", "root"); file("src/AGENTS.md", "7", "source"); file("src/main/AGENTS.md", "7", "main");
        var result = instructions.prepare(ticket("task"));
        assertThat(result.sources()).extracting(source -> source.path()).containsExactly("AGENTS.md", "src/AGENTS.md", "src/main/AGENTS.md");
        assertThat(result.sources()).extracting(source -> source.content()).containsExactly("root", "source", "main");
        verify(exercises, never()).read(anyString(), anyString(), eq("other/AGENTS.md"));
        var payload = (UserMessage) ChatMessageDeserializer.messageFromJson(instructions.payloads(result).getFirst());
        assertThat(payload.singleText()).contains("never permissions", "Deeper scopes", "root", "main");
        assertThat(instructions.decode(instructions.encode(result))).isEqualTo(result);
    }
    @Test void newTaskGetsRootWithoutOtherTasksScopesAndRefreshesAtPreparation() throws Exception {
        snapshot("7", List.of("AGENTS.md", "other/AGENTS.md")); file("AGENTS.md", "7", "old");
        var previous = instructions.prepare(ticket("new-task"));
        snapshot("8", List.of("AGENTS.md", "other/AGENTS.md")); file("AGENTS.md", "8", "new");
        var next = instructions.prepare(ticket("new-task"));
        assertThat(previous.sources()).hasSize(1); assertThat(previous.sources().getFirst().content()).isEqualTo("old");
        assertThat(next.revision()).isEqualTo("8"); assertThat(next.sources().getFirst().content()).isEqualTo("new");
    }
    @Test void missingOversizedAndReadFailuresAreExplicitWithoutPartialGuidance() throws Exception {
        snapshot("7", List.of("src/AGENTS.md", "src/main/AGENTS.md", "src/main/Main.java"));
        when(tasks.instructionPaths("task")).thenReturn(List.of("{\"path\":\"src/main/Main.java\"}"));
        file("src/AGENTS.md", "7", "大".repeat(3000));
        when(exercises.read("session", "exercise", "src/main/AGENTS.md")).thenThrow(new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE));
        var result = instructions.prepare(ticket("task"));
        assertThat(result.sources()).extracting(source -> source.status()).containsExactly("MISSING", "LIMIT_EXCEEDED", "READ_FAILED");
        assertThat(result.sources()).allMatch(source -> source.content() == null);
    }
    @Test void boundsTotalContentAndDirectorySourcesAndHonorsCancellation() throws Exception {
        var paths = new ArrayList<String>();
        var arguments = new ArrayList<String>();
        paths.add("AGENTS.md"); file("AGENTS.md", "7", "x".repeat(8192));
        for (int index = 0; index < 32; index++) {
            String directory = "dir" + index;
            paths.add(directory + "/AGENTS.md"); paths.add(directory + "/Main.java");
            file(directory + "/AGENTS.md", "7", "x".repeat(8192));
            arguments.add("{\"path\":\"" + directory + "/Main.java\"}");
        }
        snapshot("7", paths); when(tasks.instructionPaths("task")).thenReturn(arguments);
        var result = instructions.prepare(ticket("task"));
        assertThat(result.scopeTruncated()).isTrue(); assertThat(result.sources()).hasSize(32);
        assertThat(result.sources().stream().filter(source -> "LOADED".equals(source.status()))).hasSize(4);
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> instructions.prepare(ticket("task"))).isInstanceOf(InterruptedException.class); }
        finally { Thread.interrupted(); }
    }
    @Test void neverMixesVersions() {
        snapshot("7", List.of("AGENTS.md")); file("AGENTS.md", "8", "changed");
        assertThatThrownBy(() -> instructions.prepare(ticket("task"))).isInstanceOf(BusinessException.class)
            .extracting(failure -> ((BusinessException) failure).errorCode().code()).isEqualTo("REVISION_CONFLICT");
    }
}
