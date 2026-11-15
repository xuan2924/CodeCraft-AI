package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotReferenceSelectionDTO;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.model.dto.TestLogResponse;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.dto.TestHistoryResponse;
import ai.codecraft.modules.workspace.service.ExerciseService;
import ai.codecraft.modules.workspace.service.TestTaskService;
import ai.codecraft.modules.workspace.service.CommandTaskService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CopilotReferencesTest {
    private final ExerciseService files = mock(ExerciseService.class);
    private final TestTaskService tests = mock(TestTaskService.class);
    private final ObjectMapper json = new ObjectMapper();
    private CopilotReferences references;
    @BeforeEach void setup() {
        var secrets = mock(IdentitySecrets.class);
        when(secrets.fingerprint(anyString())).thenAnswer(call -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(call.<String>getArgument(0).getBytes(StandardCharsets.UTF_8))));
        references = new CopilotReferences(files, tests, mock(CommandTaskService.class), mock(WorkspaceCoordinationStore.class), secrets, json);
        when(files.find("session", "exercise")).thenReturn(new ExerciseResponse("exercise", "problem", "workspace", "preparation", "ACTIVE", "7", "1", List.of("src/Main.java"), Instant.EPOCH, Instant.EPOCH.plusSeconds(100), "UNAVAILABLE_NOT_IMPLEMENTED", null, null, null, null));
        when(files.read("session", "exercise", "src/Main.java")).thenReturn(new WorkspaceFileResponse("workspace", "7", "src/Main.java", "中文abc"));
    }
    @Test void utf8PagesAndFixedSelectionsDoNotSubstituteCurrentContent() {
        var first = references.page("session", "exercise", "CODE", "src/Main.java", null, 0, 4);
        assertThat(first.text()).isEqualTo("中"); assertThat(first.nextOffset()).isEqualTo(3); assertThat(first.sourceIncomplete()).isFalse();
        var second = references.page("session", "exercise", "CODE", "src/Main.java", first.version(), 3, 4);
        assertThat(second.text()).isEqualTo("文a"); assertThat(second.nextOffset()).isEqualTo(7);
        var selection = new CopilotReferenceSelectionDTO(first.kind(), first.source(), first.version(), first.offset(), first.limit(), first.fingerprint());
        assertThat(references.prepare("session", "exercise", List.of(selection)).payload()).contains("中", "workspace:7", "untrusted source");
        when(files.read("session", "exercise", "src/Main.java")).thenReturn(new WorkspaceFileResponse("workspace", "8", "src/Main.java", "replaced"));
        assertThatThrownBy(() -> references.prepare("session", "exercise", List.of(selection))).isInstanceOf(BusinessException.class).hasMessage("COPILOT_REFERENCE_CHANGED");
    }
    @Test void underlyingLogLossIsDistinctFromRetainedPageContinuation() throws Exception {
        String id = "10000000-0000-0000-0000-000000000001";
        when(tests.query("session", "exercise", id)).thenReturn(new TestTaskResponse(id, "exercise", "execution", "workspace", "7", "base", "FAILED", null,
            Instant.EPOCH, Instant.EPOCH, 1, true, "DONE", "DONE", "12", true, List.of(new TestLogResponse("stderr", "中".repeat(1000))), null, null));
        var retained = tests.query("session", "exercise", id);
        when(tests.recent("session", "exercise")).thenReturn(new TestHistoryResponse(List.of(retained), null));
        assertThat(references.sources("session", "exercise", "TEST_LOG", null).sources()).singleElement().satisfies(source -> {
            assertThat(source.id()).isEqualTo(id); assertThat(source.label()).isEqualTo("base"); assertThat(source.revision()).isEqualTo("7");
        });
        var page = references.page("session", "exercise", "TEST_LOG", id, null, 0, 128);
        assertThat(page.sourceIncomplete()).isTrue(); assertThat(page.droppedBytes()).isEqualTo("12"); assertThat(page.nextOffset()).isNotNull();
        var tool = new CopilotReferenceService(references, mock(CopilotConversationStore.class), json);
        var result = json.readTree(tool.execute("session", "exercise", "{\"kind\":\"TEST_LOG\",\"source\":\"" + id + "\",\"version\":\"" + page.version() + "\",\"offset\":" + page.nextOffset() + "}"));
        assertThat(result.path("code").textValue()).isEqualTo("OK"); assertThat(result.path("page").path("sourceIncomplete").booleanValue()).isTrue();
        assertThat(json.readTree(tool.execute("session", "exercise", "{\"kind\":\"FILE\",\"source\":\"missing\"}")).path("code").textValue()).isEqualTo("COPILOT_REFERENCE_UNAVAILABLE");
    }
    @Test void invalidBoundariesAndTamperedFingerprintsFailWithoutEmptySuccess() {
        assertThatThrownBy(() -> references.page("session", "exercise", "FILE", "src/Main.java", null, 1, 128)).hasMessage("COPILOT_INVALID_REQUEST");
        assertThatThrownBy(() -> references.page("session", "exercise", "FILE", "src/Main.java", null, 0, 1)).hasMessage("COPILOT_INVALID_REQUEST");
        var first = references.page("session", "exercise", "FILE", "src/Main.java", null, 0, 2048);
        assertThat(first.nextOffset()).isNull();
        var forged = new CopilotReferenceSelectionDTO(first.kind(), first.source(), first.version(), 0, 2048, "wrong");
        assertThatThrownBy(() -> references.prepare("session", "exercise", List.of(forged))).hasMessage("COPILOT_REFERENCE_CHANGED");
        assertThatThrownBy(() -> references.prepare("session", "exercise", List.of(forged, forged))).hasMessage("COPILOT_INVALID_REQUEST");
    }
}
