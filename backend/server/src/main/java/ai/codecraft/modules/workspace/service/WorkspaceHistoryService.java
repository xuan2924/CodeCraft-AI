package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.model.dto.FileChangeRequest;
import ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryPreviewResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreRequest;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreResponse;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** External history reads, capacity measurement and existing write dispatch stay outside transactions. */
@Service
public class WorkspaceHistoryService {
    private final WorkspaceHistoryStore history;
    private final WorkspaceWriteTransactionService writes;
    private final WorkspaceWriteCoordinator coordinator;
    private final SandboxWorkspaceGateway sandbox;
    private final StorageCapacityService capacity;
    private final WorkspaceBatchCodec codec;
    private final IdentitySecrets secrets;
    private final ObjectMapper json;

    public WorkspaceHistoryService(WorkspaceHistoryStore history, WorkspaceWriteTransactionService writes, WorkspaceWriteCoordinator coordinator,
        SandboxWorkspaceGateway sandbox, StorageCapacityService capacity, WorkspaceBatchCodec codec, IdentitySecrets secrets,
        @Qualifier("workspaceJson") ObjectMapper json) {
        this.history = history; this.writes = writes; this.coordinator = coordinator; this.sandbox = sandbox;
        this.capacity = capacity; this.codec = codec; this.secrets = secrets; this.json = json;
    }

    public WorkspaceHistoryResponse recent(String session, String exercise, long before) { return history.recent(session, exercise, before); }

    public WorkspaceHistoryPreviewResponse preview(String session, String exercise, String change, String revision, String mode, String targetRevision) {
        id(change);
        long expected = revision(revision);
        Long target = targetRevision == null ? null : revision(targetRevision);
        if (mode == null || !List.of("DIFF", "UNDO", "ROLLBACK").contains(mode)
            || "ROLLBACK".equals(mode) != (target != null)) fail(WorkspaceErrorCode.INVALID_REQUEST);
        var source = history.ticket(session, exercise, change, expected);
        if (target != null && target != source.baseRevision() && target != source.resultRevision()) fail(WorkspaceErrorCode.INVALID_REQUEST);
        WorkspaceHistoryPreviewResponse result;
        try { result = WorkspaceHistoryProjection.project(source, expected, mode, target, sandbox.historyPreview(source, expected, mode, target)); }
        catch (StatusRuntimeException failure) {
            var code = switch (failure.getStatus().getCode()) {
                case NOT_FOUND -> WorkspaceErrorCode.CODE_HISTORY_UNAVAILABLE;
                case ABORTED -> WorkspaceErrorCode.REVISION_CONFLICT;
                default -> WorkspaceErrorCode.WORKSPACE_UNAVAILABLE;
            };
            throw new BusinessException(code, failure);
        } catch (IllegalStateException failure) { throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE, failure); }
        history.deliver(session, source, expected);
        return result;
    }

    public WorkspaceRestoreResponse restore(String session, String exercise, String key, String credential, WorkspaceRestoreRequest input) {
        validate(input);
        String fingerprint = secrets.fingerprint(encode(input));
        var resolution = history.resolve(session, exercise, key, fingerprint, input);
        if (resolution.operation() != null) return new WorkspaceRestoreResponse(exercise, key, input, finish(session, resolution.operation()), null);
        if (resolution.notAcceptedReason() != null) return new WorkspaceRestoreResponse(exercise, key, input, null, resolution.notAcceptedReason());
        WorkspaceOperationEntity operation;
        try { operation = admit(session, exercise, key, credential, input, fingerprint); }
        catch (BusinessException failure) {
            // A duplicate may have committed while this request was reading its
            // preview. Prefer that receipt over a now-stale new-write check.
            var resolved = history.resolve(session, exercise, key, fingerprint, input);
            if (resolved.operation() != null) return new WorkspaceRestoreResponse(exercise, key, input, finish(session, resolved.operation()), null);
            if (resolved.notAcceptedReason() != null) return new WorkspaceRestoreResponse(exercise, key, input, null, resolved.notAcceptedReason());
            throw failure;
        }
        return new WorkspaceRestoreResponse(exercise, key, input, finish(session, operation), null);
    }

    private WorkspaceOperationEntity admit(String session, String exercise, String key, String credential, WorkspaceRestoreRequest input, String fingerprint) {
        history.ready(session, exercise, input);
        String transition = writes.ensureOpen(session, exercise, credential);
        if (transition != null) coordinator.tryDrive(transition);
        var preview = preview(session, exercise, input.changeId(), input.expectedRevision(), input.mode(), input.targetRevision());
        if (!preview.conflicts().isEmpty()) fail(WorkspaceErrorCode.RESTORE_CONFLICT);
        if (!preview.fingerprint().equals(input.previewFingerprint())) fail(WorkspaceErrorCode.RESTORE_PREVIEW_CHANGED);
        var batch = new FileBatchRequest(input.expectedRevision(), preview.changes().stream()
            .map(change -> new FileChangeRequest(change.path(), change.kind(), change.content())).toList());
        boolean unchanged = batch.changes().isEmpty();
        String payload = unchanged ? encode(batch) : codec.encode(batch);
        return capacity.admit(session, exercise, Set.of(input.changeId()),
            measured -> history.admit(session, exercise, key, credential, input, fingerprint, payload, unchanged, measured));
    }

    private WorkspaceOperationResponse finish(String session, WorkspaceOperationEntity operation) {
        if (List.of("PENDING", "PENDING_RECONCILIATION").contains(operation.state())) coordinator.tryMutation(operation);
        var result = writes.result(session, operation.id());
        if ("REJECTED".equals(result.state())) throw new BusinessException(WorkspaceErrorCode.valueOf(result.failureCode()));
        return result;
    }

    private void validate(WorkspaceRestoreRequest input) {
        if (input == null || input.mode() == null || !List.of("UNDO", "ROLLBACK").contains(input.mode())
            || input.previewFingerprint() == null || !input.previewFingerprint().matches("[0-9a-f]{64}")) fail(WorkspaceErrorCode.INVALID_REQUEST);
        id(input.changeId());
        revision(input.expectedRevision());
        if ("ROLLBACK".equals(input.mode()) != (input.targetRevision() != null)) fail(WorkspaceErrorCode.INVALID_REQUEST);
        if (input.targetRevision() != null) revision(input.targetRevision());
    }

    private String encode(Object input) {
        try { return json.writeValueAsString(input); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode validated restore intent", error); }
    }
    private static long revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) fail(WorkspaceErrorCode.INVALID_REQUEST);
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST, error); }
    }
    private static void id(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) fail(WorkspaceErrorCode.INVALID_REQUEST);
    }
    private static void fail(WorkspaceErrorCode code) { throw new BusinessException(code); }
}
