package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.service.ExerciseService;
import ai.codecraft.modules.workspace.service.WorkspaceBatchCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CopilotProposalPreparationTest {
    private final ExerciseService files = mock(ExerciseService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CopilotProposalPreparation prepare = new CopilotProposalPreparation(files, new WorkspaceBatchCodec(json), json);
    private final String input = """
        {"expected_revision":"7","changes":[
          {"path":"src/main/java/Before.java","kind":"DELETE"},
          {"path":"src/main/java/After.java","kind":"CREATE","content":"class After {}"}]}
        """;

    @Test void capturesTheWholeRenameBatchAgainstOneSavedRevisionWithoutWriting() {
        when(files.find("session", "exercise")).thenReturn(snapshot("7"));
        when(files.read("session", "exercise", "src/main/java/Before.java"))
            .thenReturn(new WorkspaceFileResponse("instance", "7", "src/main/java/Before.java", "class Before {}"));
        var result = prepare.prepare("session", "exercise", input);
        assertThat(result.revision()).isEqualTo("7");
        assertThat(result.files()).hasSize(2);
        assertThat(result.files().getFirst().path()).isEqualTo("src/main/java/After.java");
        assertThat(result.files().getFirst().before()).isNull();
        assertThat(result.files().getLast().before()).isEqualTo("class Before {}");
        assertThat(result.files().getLast().after()).isNull();
        assertThat(result.batchPayload()).contains("\"expectedRevision\":\"7\"");
    }
    @Test void rejectsAmbiguousJsonAndProtectedPathsBeforeReadingWorkspaceContents() {
        for (String invalid : List.of("{", input + "{}", input.replace("\"expected_revision\":\"7\"", "\"expected_revision\":\"7\",\"expected_revision\":\"8\""),
            input.replace("\"changes\"", "\"unknown\":true,\"changes\""), input.replace("\"changes\"", "\"mode\":\"AUTO\",\"changes\""), input.replace("src/main/java/After.java", "../private"))) {
            assertThatThrownBy(() -> prepare.prepare("session", "exercise", invalid)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> prepare.prepare("session", "exercise", input.replace("src/main/java/After.java", "authority/tests.java"))).hasMessage("PATH_FORBIDDEN");
        verify(files, never()).find(anyString(), anyString());
    }
    @Test void neverBuildsADiffFromMixedOrChangedRevisions() {
        when(files.find("session", "exercise")).thenReturn(snapshot("7"));
        when(files.read("session", "exercise", "src/main/java/Before.java"))
            .thenReturn(new WorkspaceFileResponse("instance", "8", "src/main/java/Before.java", "concurrent edit"));
        assertThatThrownBy(() -> prepare.prepare("session", "exercise", input)).hasMessage("REVISION_CONFLICT");
    }

    @Test void exactEditsUseOriginalOffsetsAndPreserveCrLf() throws Exception {
        var edits = json.readTree("[{\"oldText\":\"first\\nsecond\",\"newText\":\"new\\ntext\"}]");
        assertThat(CopilotProposalPreparation.exactEdit("a", "first\r\nsecond\r\nthird", edits)).isEqualTo("new\r\ntext\r\nthird");
        var independent = json.readTree("[{\"oldText\":\"abc\",\"newText\":\"xyz\"},{\"oldText\":\"xyz\",\"newText\":\"end\"}]");
        assertThat(CopilotProposalPreparation.exactEdit("a", "abc xyz", independent)).isEqualTo("xyz end");
        assertThatThrownBy(() -> CopilotProposalPreparation.exactEdit("a", "abc abc", independent)).hasMessage("EDIT_AMBIGUOUS_MATCH");
        assertThatThrownBy(() -> CopilotProposalPreparation.exactEdit("a", "missing", independent)).hasMessage("EDIT_MATCH_NOT_FOUND");
        var overlap = json.readTree("[{\"oldText\":\"abc\",\"newText\":\"x\"},{\"oldText\":\"bc\",\"newText\":\"y\"}]");
        assertThatThrownBy(() -> CopilotProposalPreparation.exactEdit("a", "abc", overlap)).hasMessage("EDIT_OVERLAPPING_MATCH");
    }

    @Test void modernEditAndWritePrepareTheSameAtomicApprovalEvidence() {
        when(files.find("session", "exercise")).thenReturn(snapshot("7"));
        when(files.read("session", "exercise", "src/main/java/Before.java"))
            .thenReturn(new WorkspaceFileResponse("instance", "7", "src/main/java/Before.java", "class Before {}"));
        var edited = prepare.prepare("session", "exercise", "edit", """
            {"expected_revision":"7","files":[
              {"path":"src/main/java/Before.java","action":"EDIT","edits":[{"oldText":"Before","newText":"Changed"}]},
              {"path":"src/main/java/New.java","action":"CREATE","content":"class New {}"}]}
            """);
        assertThat(edited.files()).hasSize(2);
        assertThat(edited.files().getFirst().before()).isEqualTo("class Before {}");
        assertThat(edited.files().getFirst().after()).isEqualTo("class Changed {}");
        var written = prepare.prepare("session", "exercise", "write", """
            {"expected_revision":"7","path":"src/main/java/Before.java","content":"replacement"}
            """);
        assertThat(written.files().getFirst().kind()).isEqualTo("UPDATE");
        assertThat(written.files().getFirst().after()).isEqualTo("replacement");
        assertThatThrownBy(() -> prepare.prepare("session", "exercise", "write", """
            {"expected_revision":"6","path":"src/main/java/Before.java","content":"stale"}
            """)).hasMessage("REVISION_CONFLICT");
    }

    private ExerciseResponse snapshot(String revision) {
        return new ExerciseResponse("exercise", "problem", "instance", "prepare", "ACTIVE", revision, "1", List.of("src/main/java/Before.java"),
            null, null, "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null, null, null);
    }
}
