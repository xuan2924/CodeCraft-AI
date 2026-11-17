package ai.codecraft.modules.copilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.ExerciseService;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.workspace.v1.GrepWorkspaceRequest;
import ai.codecraft.workspace.v1.GrepWorkspaceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CopilotWorkspaceGrepTest {
    private final ExerciseTransactionService store = mock(ExerciseTransactionService.class);
    private final SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
    private final ExerciseService service = new ExerciseService(store, sandbox, new ObjectMapper(), null, null, null, null, null, null, null, null);

    private ExerciseEntity exercise(long revision) {
        var exercise = mock(ExerciseEntity.class);
        when(exercise.workspaceInstanceId()).thenReturn("instance");
        when(exercise.revision()).thenReturn(revision);
        return exercise;
    }
    private GrepWorkspaceResponse response() {
        return GrepWorkspaceResponse.newBuilder().setWorkspaceInstanceId("instance").setRevision(7).addLines("src/a:1: needle").build();
    }
    @Test void usesOneRpcWithServerBaselineAndReturnsBoundedResults() {
        doReturn(exercise(7)).when(store).find("session", "exercise");
        when(sandbox.grep(any())).thenReturn(response());
        var result = service.grep("session", "exercise", "needle", "src/", false, 2, 20);
        assertThat(result.revision()).isEqualTo("7");
        assertThat(result.lines()).containsExactly("src/a:1: needle");
        var request = ArgumentCaptor.forClass(GrepWorkspaceRequest.class);
        verify(sandbox).grep(request.capture());
        assertThat(request.getValue().getExpectedRevision()).isEqualTo(7);
        assertThat(request.getValue().getPathPrefix()).isEqualTo("src/");
        assertThat(request.getValue().getContextLines()).isEqualTo(2);
        assertThat(request.getValue().getLimit()).isEqualTo(20);
    }
    @Test void neverReturnsContentAfterAuthorizationIsRevokedInFlight() {
        var initial = exercise(7);
        when(store.find("session", "exercise")).thenReturn(initial).thenThrow(new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND));
        when(sandbox.grep(any())).thenReturn(response());
        assertThatThrownBy(() -> service.grep("session", "exercise", "needle", "", false, 0, 1)).hasMessage("RESOURCE_NOT_FOUND");
    }
    @Test void checksAuthorizationBeforeDispatchAndRejectsChangedRevisionsAfterward() {
        when(store.find("session", "exercise")).thenThrow(new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.grep("session", "exercise", "needle", "", false, 0, 1)).hasMessage("RESOURCE_NOT_FOUND");
        verify(sandbox, never()).grep(any());
        org.mockito.Mockito.reset(store);
        doReturn(exercise(7), exercise(8)).when(store).find("session", "exercise");
        when(sandbox.grep(any())).thenReturn(response());
        assertThatThrownBy(() -> service.grep("session", "exercise", "needle", "", false, 0, 1)).hasMessage("REVISION_CONFLICT");
    }
    @Test void mapsRpcConflictAndRejectsIncorrectIdentityAndOversizedResults() {
        doReturn(exercise(7)).when(store).find("session", "exercise");
        when(sandbox.grep(any())).thenThrow(Status.ABORTED.asRuntimeException());
        assertThatThrownBy(() -> service.grep("session", "exercise", "needle", "", false, 0, 1)).hasMessage("REVISION_CONFLICT");
        org.mockito.Mockito.reset(sandbox);
        when(sandbox.grep(any())).thenReturn(response().toBuilder().setWorkspaceInstanceId("other").build(),
            response().toBuilder().addLines("大".repeat(30000)).build());
        for (int index = 0; index < 2; index++) {
            assertThatThrownBy(() -> service.grep("session", "exercise", "needle", "", false, 0, 1)).hasMessage("WORKSPACE_UNAVAILABLE");
        }
    }
}
