package ai.codecraft.modules.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreRequest;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreResolutionDTO;
import ai.codecraft.modules.workspace.model.entity.WorkspaceChangeEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.workspace.v1.FileChange;
import ai.codecraft.workspace.v1.WorkspaceFileDifference;
import ai.codecraft.workspace.v1.WorkspaceHistoryPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkspaceHistoryServiceTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String EXERCISE = "22222222-2222-4222-8222-222222222222";
    private static final String PATH = "src/main/java/Main.java";
    private final WorkspaceHistoryStore history = mock(WorkspaceHistoryStore.class);
    private final WorkspaceWriteTransactionService writes = mock(WorkspaceWriteTransactionService.class);
    private final WorkspaceWriteCoordinator coordinator = mock(WorkspaceWriteCoordinator.class);
    private final SandboxWorkspaceGateway sandbox = mock(SandboxWorkspaceGateway.class);
    private final StorageCapacityService capacity = mock(StorageCapacityService.class);
    private final IdentitySecrets secrets = mock(IdentitySecrets.class);
    private final ObjectMapper json = new ObjectMapper();
    private final WorkspaceHistoryService service = new WorkspaceHistoryService(history, writes, coordinator, sandbox, capacity,
        new WorkspaceBatchCodec(json), secrets, json);
    private final WorkspaceChangeEntity source = new WorkspaceChangeEntity(ID, EXERCISE, ID, "MANUAL", 0, 1, null, null, null, true, Instant.EPOCH);

    @Test void originalUnknownIntentIsReconciledWithoutReadingHistoryOrObtainingNewAuthority() {
        var input = new WorkspaceRestoreRequest(ID, "UNDO", "1", null, "a".repeat(64));
        var operation = operation("PENDING_RECONCILIATION");
        when(secrets.fingerprint(anyString())).thenReturn("request");
        when(history.resolve("session", EXERCISE, "original", "request", input)).thenReturn(new WorkspaceRestoreResolutionDTO(operation, null));
        when(writes.result("session", ID)).thenReturn(result(true));
        assertThat(service.restore("session", EXERCISE, "original", "rotated-control", input).operation().revision()).isEqualTo("2");
        verify(coordinator).tryMutation(operation);
        verify(history, never()).ready(any(), any(), any());
        verify(writes, never()).ensureOpen(any(), any(), any());
        verifyNoInteractions(sandbox, capacity);
    }

    @Test void aConcurrentReceiptWinsOverAStalePreflightRejection() {
        var input = new WorkspaceRestoreRequest(ID, "UNDO", "1", null, "a".repeat(64));
        var operation = operation("SUCCEEDED");
        when(secrets.fingerprint(anyString())).thenReturn("request");
        when(history.resolve("session", EXERCISE, "original", "request", input))
            .thenReturn(new WorkspaceRestoreResolutionDTO(null, null), new WorkspaceRestoreResolutionDTO(operation, null));
        doThrow(new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT)).when(history).ready("session", EXERCISE, input);
        when(writes.result("session", ID)).thenReturn(new WorkspaceOperationResponse(ID, EXERCISE, "SUCCEEDED", "status", "2", true, null));
        assertThat(service.restore("session", EXERCISE, "original", "control", input).operation().revision()).isEqualTo("2");
        verifyNoInteractions(sandbox, capacity);
    }

    @Test void immutableNonAcceptanceProofResolvesTheExactIntentWithoutCreatingAWrite() {
        var input = new WorkspaceRestoreRequest(ID, "UNDO", "1", null, "a".repeat(64));
        when(secrets.fingerprint(anyString())).thenReturn("request");
        when(history.resolve("session", EXERCISE, "original", "request", input))
            .thenReturn(new WorkspaceRestoreResolutionDTO(null, "CODE_HISTORY_UNAVAILABLE"));
        var result = service.restore("session", EXERCISE, "original", "obsolete", input);
        assertThat(result.requestKey()).isEqualTo("original");
        assertThat(result.intent()).isEqualTo(input);
        assertThat(result.operation()).isNull();
        assertThat(result.notAcceptedReason()).isEqualTo("CODE_HISTORY_UNAVAILABLE");
        verifyNoInteractions(writes, coordinator, sandbox, capacity);
    }

    @Test void confirmationCannotSubstituteANewDiffAndReauthorizesReadDelivery() throws Exception {
        var preview = projection("UNDO", false);
        setup(preview);
        assertCode(() -> service.restore("session", EXERCISE, "original", "control",
            new WorkspaceRestoreRequest(ID, "UNDO", "1", null, "0".repeat(64))), "RESTORE_PREVIEW_CHANGED");
        verify(history).deliver("session", source, 1);
        verifyNoInteractions(capacity);
        verify(coordinator, never()).tryMutation(any());
    }

    @Test void conflictEvenWithAnEmptyDiffCannotBecomeANoOpSuccess() throws Exception {
        var preview = signed(base("UNDO").addConflicts(PATH));
        setup(preview);
        assertCode(() -> service.restore("session", EXERCISE, "original", "control",
            new WorkspaceRestoreRequest(ID, "UNDO", "1", null, preview.getFingerprint())), "RESTORE_CONFLICT");
        verifyNoInteractions(capacity);
        verify(coordinator, never()).tryMutation(any());
    }

    @Test void acceptedRestoreUsesCanonicalExistingBatchAndProtectsItsSelectedSource() throws Exception {
        var preview = projection("UNDO", false);
        setup(preview);
        var input = new WorkspaceRestoreRequest(ID, "UNDO", "1", null, preview.getFingerprint());
        var operation = operation("PENDING");
        admission(operation);
        when(writes.result("session", ID)).thenReturn(result(true));
        assertThat(service.restore("session", EXERCISE, "original", "control", input).operation().changed()).isTrue();
        var payload = ArgumentCaptor.forClass(String.class);
        verify(history).admit(eq("session"), eq(EXERCISE), eq("original"), eq("control"), eq(input), eq("request"), payload.capture(), eq(false), any());
        assertThat(json.readTree(payload.getValue()).path("changes").get(0).path("content").asText()).isEqualTo("old");
        verify(coordinator).tryMutation(operation);
        verify(capacity).admit(eq("session"), eq(EXERCISE), eq(Set.of(ID)), any());
    }

    @Test void unchangedRestoreStillHasADurableReceiptButNeverDispatchesAnEmptyBatch() throws Exception {
        var preview = projection("ROLLBACK", true);
        setup(preview);
        var input = new WorkspaceRestoreRequest(ID, "ROLLBACK", "1", "1", preview.getFingerprint());
        admission(operation("SUCCEEDED"));
        when(writes.result("session", ID)).thenReturn(result(false));
        assertThat(service.restore("session", EXERCISE, "original", "control", input).operation().changed()).isFalse();
        var payload = ArgumentCaptor.forClass(String.class);
        verify(history).admit(eq("session"), eq(EXERCISE), eq("original"), eq("control"), eq(input), eq("request"), payload.capture(), eq(true), any());
        assertThat(json.readTree(payload.getValue()).path("changes").isEmpty()).isTrue();
        verify(coordinator, never()).tryMutation(any());
    }

    @Test void missingOrForgedProjectionNeverBecomesAValidPreviewAndRetainsTechnicalCause() throws Exception {
        var preview = projection("UNDO", false);
        setup(preview);
        var missing = Status.NOT_FOUND.asRuntimeException();
        when(sandbox.historyPreview(source, 1, "UNDO", null)).thenThrow(missing);
        assertThatThrownBy(() -> service.preview("session", EXERCISE, ID, "1", "UNDO", null))
            .isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.code()).isEqualTo("CODE_HISTORY_UNAVAILABLE"); assertThat(error.getCause()).isSameAs(missing);
            });
        doReturn(preview.toBuilder().setCurrentRevision(2).build()).when(sandbox).historyPreview(source, 1, "UNDO", null);
        assertCode(() -> service.preview("session", EXERCISE, ID, "1", "UNDO", null), "WORKSPACE_UNAVAILABLE");
        verify(history, never()).deliver(any(), any(), anyLong());
    }

    private void setup(WorkspaceHistoryPreview preview) {
        when(secrets.fingerprint(anyString())).thenReturn("request");
        when(history.resolve(any(), any(), any(), any(), any())).thenReturn(new WorkspaceRestoreResolutionDTO(null, null));
        when(history.ticket("session", EXERCISE, ID, 1)).thenReturn(source);
        when(sandbox.historyPreview(eq(source), eq(1L), anyString(), any())).thenReturn(preview);
    }
    private void admission(WorkspaceOperationEntity operation) {
        when(capacity.admit(eq("session"), eq(EXERCISE), eq(Set.of(ID)), any())).thenAnswer(call -> {
            Function<StorageMeasurement, WorkspaceOperationEntity> accept = call.getArgument(3);
            return accept.apply(null);
        });
        when(history.admit(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(operation);
    }
    private WorkspaceOperationEntity operation(String state) {
        return new WorkspaceOperationEntity(ID, "owner", EXERCISE, ID, "FILE_BATCH", EXERCISE, "original", "payload", state, Instant.EPOCH);
    }
    private WorkspaceOperationResponse result(boolean changed) {
        return new WorkspaceOperationResponse(ID, EXERCISE, "SUCCEEDED", "/api/operations/" + ID, changed ? "2" : "1", changed, null);
    }
    private static WorkspaceHistoryPreview.Builder base(String mode) {
        return WorkspaceHistoryPreview.newBuilder().setWorkspaceInstanceId(ID).setBaseRevision(0).setResultRevision(1).setCurrentRevision(1).setMode(mode);
    }
    private static WorkspaceHistoryPreview projection(String mode, boolean empty) throws Exception {
        var result = base(mode);
        if ("ROLLBACK".equals(mode)) result.setTargetRevision(1);
        if (!empty) result.addChanges(WorkspaceFileDifference.newBuilder().setPreviousContent("new")
            .setChange(FileChange.newBuilder().setKind("UPDATE").setPath(PATH).setContent("old")));
        return signed(result);
    }
    private static WorkspaceHistoryPreview signed(WorkspaceHistoryPreview.Builder value) throws Exception {
        return value.setFingerprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.clearFingerprint().build().toByteArray()))).build();
    }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
