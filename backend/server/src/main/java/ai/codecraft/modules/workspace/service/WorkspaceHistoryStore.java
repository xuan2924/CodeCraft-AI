package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.WorkspaceChangeResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceHistoryResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreRequest;
import ai.codecraft.modules.workspace.model.dto.WorkspaceRestoreResolutionDTO;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceChangeEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceRestoreEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.CommandTaskRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceHistoryRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short ownership/reference/intent transactions; never reads sandbox files or dispatches writes. */
@Service
public class WorkspaceHistoryStore {
    private final IdentityTransactionService identity;
    private final ExerciseRepository exercises;
    private final WorkspaceWriteRepository controls;
    private final CommandTaskRepository commands;
    private final WorkspaceHistoryRepository history;
    private final WorkspaceWriteTransactionService writes;

    public WorkspaceHistoryStore(IdentityTransactionService identity, ExerciseRepository exercises, WorkspaceWriteRepository controls,
        CommandTaskRepository commands, WorkspaceHistoryRepository history, WorkspaceWriteTransactionService writes) {
        this.identity = identity; this.exercises = exercises; this.controls = controls; this.commands = commands;
        this.history = history; this.writes = writes;
    }

    @Transactional
    public WorkspaceHistoryResponse recent(String session, String exercise, long before) {
        owned(identity.authorizePrivateDelivery(session).userId(), exercise);
        if (before < 0) fail(WorkspaceErrorCode.INVALID_REQUEST);
        var page = history.recent(exercise, before);
        return new WorkspaceHistoryResponse(exercise, page.stream().limit(20).map(value -> new WorkspaceChangeResponse(value.id(), exercise,
            value.source(), Long.toString(value.baseRevision()), Long.toString(value.resultRevision()), value.taskId(), value.conversationId(),
            value.originChangeId(), value.retained(), value.recordedAt())).toList(), page.size() > 20 ? Long.toString(page.get(19).resultRevision()) : null);
    }

    @Transactional
    public WorkspaceChangeEntity ticket(String session, String exercise, String change, long expectedRevision) {
        String owner = identity.authorizePrivateDelivery(session).userId();
        owned(owner, exercise);
        controls.lock(exercise);
        return selected(owned(owner, exercise), change, expectedRevision);
    }

    /** Rechecks delivery after external reading, including concurrent retirement and revision changes. */
    @Transactional
    public void deliver(String session, WorkspaceChangeEntity original, long expectedRevision) {
        var current = ticket(session, original.exerciseId(), original.id(), expectedRevision);
        if (!current.equals(original)) throw new IllegalStateException("Historical source changed during preview");
    }

    /** No receipt plus a monotonic barrier resolves an unknown intent without manufacturing a write. */
    @Transactional
    public WorkspaceRestoreResolutionDTO resolve(String session, String exercise, String key, String fingerprint, WorkspaceRestoreRequest input) {
        var owner = identity.authorizePrivateDelivery(session).userId();
        owned(owner, exercise);
        key(key);
        controls.lock(exercise);
        var current = owned(owner, exercise);
        var previous = previous(owner, exercise, key, fingerprint);
        if (previous != null) return new WorkspaceRestoreResolutionDTO(previous, null);
        var source = history.find(input.changeId());
        if (source != null && source.exerciseId().equals(exercise)) {
            // Retired references are never rearmed; code revisions never rewind.
            // The same coordination lock serializes this proof with every admission.
            if (!source.retained()) return new WorkspaceRestoreResolutionDTO(null, "CODE_HISTORY_UNAVAILABLE");
            if (source.workspaceInstanceId().equals(current.workspaceInstanceId()) && current.revision() != null
                && current.revision() > Long.parseLong(input.expectedRevision())) return new WorkspaceRestoreResolutionDTO(null, "REVISION_CONFLICT");
        }
        return new WorkspaceRestoreResolutionDTO(null, null);
    }

    /** New restores cannot jump ahead of an unresolved write/command; ordinary tests keep their old snapshot. */
    @Transactional
    public void ready(String session, String exercise, WorkspaceRestoreRequest input) {
        var actor = identity.authorizeMutation(session);
        if (!"USER".equals(actor.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        owned(actor.userId(), exercise);
        controls.lock(exercise);
        selected(owned(actor.userId(), exercise), input.changeId(), Long.parseLong(input.expectedRevision()));
        idle(exercise);
    }

    @Transactional
    public WorkspaceOperationEntity admit(String session, String exercise, String key, String credential, WorkspaceRestoreRequest input,
        String fingerprint, String payload, boolean unchanged, StorageMeasurement measurement) {
        var owner = identity.authorizePrivateDelivery(session).userId();
        owned(owner, exercise);
        key(key);
        controls.lock(exercise);
        var previous = previous(owner, exercise, key, fingerprint);
        if (previous != null) return previous;
        var actor = identity.authorizeMutation(session);
        if (!"USER".equals(actor.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        long revision = Long.parseLong(input.expectedRevision());
        var source = selected(owned(actor.userId(), exercise), input.changeId(), revision);
        Long target = input.targetRevision() == null ? null : Long.valueOf(input.targetRevision());
        if (!"UNDO".equals(input.mode()) && !"ROLLBACK".equals(input.mode())
            || "UNDO".equals(input.mode()) && target != null
            || "ROLLBACK".equals(input.mode()) && (target == null || target != source.baseRevision() && target != source.resultRevision())) fail(WorkspaceErrorCode.INVALID_REQUEST);
        idle(exercise);
        // The normal write admission rechecks current control, gate, deadline,
        // baseline and capacity. This intent joins the same transaction.
        var operation = writes.batch(session, exercise, key, credential, payload, revision, measurement);
        history.insertRestore(new WorkspaceRestoreEntity(operation.id(), exercise, source.id(), input.mode(), fingerprint,
            input.previewFingerprint(), revision, target));
        if (unchanged) {
            // Durable no-op receipt: never send an empty batch or create a revision.
            controls.result(operation.id(), revision, false, null);
            exercises.operationState(operation.id(), "SUCCEEDED");
            return exercises.operation(operation.id());
        }
        return operation;
    }

    private WorkspaceOperationEntity previous(String owner, String exercise, String key, String fingerprint) {
        var operation = exercises.receipt(owner, "FILE_BATCH", exercise, key);
        if (operation == null) return null;
        var restore = history.restore(operation.id());
        if (restore == null || !restore.exerciseId().equals(exercise) || !restore.requestFingerprint().equals(fingerprint)) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
        return operation;
    }

    private WorkspaceChangeEntity selected(ExerciseEntity exercise, String id, long expectedRevision) {
        var change = history.find(id);
        if (change == null || !change.exerciseId().equals(exercise.id())) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (!change.retained() || !change.workspaceInstanceId().equals(exercise.workspaceInstanceId())) fail(WorkspaceErrorCode.CODE_HISTORY_UNAVAILABLE);
        if (exercise.revision() == null || exercise.revision() != expectedRevision) fail(WorkspaceErrorCode.REVISION_CONFLICT);
        return change;
    }

    private void idle(String exercise) {
        if (!controls.unresolved(exercise).isEmpty() || commands.unsettled(exercise)) fail(WorkspaceErrorCode.RESTORE_BUSY);
    }

    private ExerciseEntity owned(String owner, String id) {
        var exercise = exercises.find(id);
        if (exercise == null || !exercise.userId().equals(owner)) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(id)) fail(WorkspaceErrorCode.EXERCISE_DELETED);
        return exercise;
    }

    private static void key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) fail(WorkspaceErrorCode.INVALID_REQUEST);
    }
    private static void fail(WorkspaceErrorCode code) { throw new BusinessException(code); }
}
