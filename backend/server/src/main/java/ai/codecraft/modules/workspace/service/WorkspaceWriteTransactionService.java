package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.ControlResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import java.nio.charset.StandardCharsets;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceMutationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceTransitionEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceWriteTransactionService {
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ExerciseRepository exercises;
    private final WorkspaceWriteRepository writes;
    private final Clock clock;
    private final StorageCapacityStore capacity;

    public WorkspaceWriteTransactionService(IdentityTransactionService identity, IdentitySecrets secrets,
        ExerciseRepository exercises, WorkspaceWriteRepository writes, Clock clock, StorageCapacityStore capacity) {
        this.identity = identity;
        this.secrets = secrets;
        this.exercises = exercises;
        this.writes = writes;
        this.clock = clock;
        this.capacity = capacity;
    }

    @Transactional
    public WorkspaceOperationEntity control(String session, String exerciseId, String kind, String key, String credential, String targetHash, StorageMeasurement measurement) {
        String user = identity.authorizeMutation(session).userId();
        ExerciseEntity exercise = owned(user, exerciseId);
        key(key);
        writes.initialize(exerciseId);
        WorkspaceControlEntity control = writes.lock(exerciseId);
        WorkspaceOperationEntity previous = exercises.receipt(user, kind, exerciseId, key);
        if (previous != null) return previous;
        writable(exercise, false);
        idle(control);
        if (kind.equals("ACQUIRE") && control.credentialHash() != null) throw new BusinessException(WorkspaceErrorCode.CONTROL_HELD);
        if (kind.equals("RELEASE")) credential(control, credential);
        if (!List.of("ACQUIRE", "TAKEOVER", "RELEASE").contains(kind)) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        WorkspaceOperationEntity operation = operation(exercise, kind, key, secrets.fingerprint(kind));
        if (!kind.equals("RELEASE")) capacity.admit(operation, measurement, StorageCapacityStore.CONTROL_GROWTH);
        writes.insertTransition(new WorkspaceTransitionEntity(operation.id(), exerciseId, kind, null, "CLOSING", targetHash));
        writes.activate(exerciseId, operation.id());
        return operation;
    }

    @Transactional(readOnly = true)
    public WorkspaceOperationEntity existingControl(String session, String exerciseId, String kind, String key) {
        String user = identity.authorization(session).userId();
        owned(user, exerciseId);
        key(key);
        return exercises.receipt(user, kind, exerciseId, key);
    }

    @Transactional(readOnly = true)
    public ProblemLanguage language(String session, String exercise) {
        String name = exercises.language(exercise, identity.authorization(session).userId());
        if (name == null) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        return ProblemLanguage.valueOf(name);
    }

    @Transactional(readOnly = true)
    public WorkspaceOperationEntity existingBatch(String session, String exerciseId, String key, String payload) {
        String user = identity.authorization(session).userId();
        owned(user, exerciseId);
        key(key);
        WorkspaceOperationEntity operation = exercises.receipt(user, "FILE_BATCH", exerciseId, key);
        if (operation != null && !operation.fingerprint().equals(secrets.fingerprint(payload))) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
        return operation;
    }

    @Transactional
    public String ensureOpen(String session, String exerciseId, String credential) {
        ExerciseEntity exercise = owned(identity.authorizeMutation(session).userId(), exerciseId);
        writable(exercise, true);
        WorkspaceControlEntity control = writes.lock(exerciseId);
        credential(control, credential);
        idle(control);
        if (control.gateState().equals("OPEN")) return null;
        String id = UUID.randomUUID().toString();
        writes.insertTransition(new WorkspaceTransitionEntity(id, exerciseId, "RECONCILE", null, "CLOSING", null));
        writes.activate(exerciseId, id);
        return id;
    }

    @Transactional
    public WorkspaceOperationEntity batch(String session, String exerciseId, String key, String credential, String payload, long expectedRevision, StorageMeasurement measurement) {
        return acceptBatch(session, exerciseId, key, credential, payload, expectedRevision, measurement, false);
    }

    /** Trusted Copilot admission after durable AUTO authorization; same FILE_BATCH intent and dispatcher. */
    @Transactional
    public WorkspaceOperationEntity batchCopilot(String session, String exerciseId, String key, String payload, long expectedRevision, StorageMeasurement measurement) {
        return acceptBatch(session, exerciseId, key, null, payload, expectedRevision, measurement, true);
    }

    private WorkspaceOperationEntity acceptBatch(String session, String exerciseId, String key, String credential, String payload,
        long expectedRevision, StorageMeasurement measurement, boolean copilot) {
        SessionAuthorizationDTO authorization = identity.authorizeMutation(session);
        if (copilot && !"USER".equals(authorization.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        ExerciseEntity exercise = owned(authorization.userId(), exerciseId);
        key(key);
        WorkspaceControlEntity control = writes.lock(exerciseId);
        if (!copilot) credential(control, credential);
        WorkspaceOperationEntity previous = exercises.receipt(authorization.userId(), "FILE_BATCH", exerciseId, key);
        String fingerprint = secrets.fingerprint(payload);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            return previous;
        }
        writable(exercise, true);
        if (writes.terminalActive(exerciseId)) throw new BusinessException(WorkspaceErrorCode.TERMINAL_BUSY);
        idle(control);
        if (!control.gateState().equals("OPEN") || control.gateEpoch() == null) throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
        // A new request may only pin the current Java baseline. A stale browser
        // draft cannot resurrect an old version that was already unreferenced
        // and collected. Existing request keys above still return their receipt.
        if (exercise.revision() == null || exercise.revision() != expectedRevision) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
        WorkspaceOperationEntity operation = operation(exercise, "FILE_BATCH", key, fingerprint);
        capacity.admit(operation, measurement, Math.addExact(payload.getBytes(StandardCharsets.UTF_8).length, StorageCapacityStore.MUTATION_OVERHEAD));
        Instant deadline = authorization.expiresAt().isBefore(exercise.deadlineAt()) ? authorization.expiresAt() : exercise.deadlineAt();
        writes.insertMutation(new WorkspaceMutationEntity(operation.id(), payload, control.gateEpoch(), deadline, false, null, null, null));
        return operation;
    }

    @Transactional(readOnly = true)
    public ControlResponse view(String session, String exerciseId, String operationId, String candidate) {
        ExerciseEntity exercise = owned(identity.authorization(session).userId(), exerciseId);
        WorkspaceControlEntity control = writes.control(exerciseId);
        String state = control == null || (control.transitionId() == null && control.fenceId() == null) ? "SUCCEEDED" : "PENDING";
        boolean delivered = candidate != null && control != null && state.equals("SUCCEEDED")
            && List.of("READY", "ACTIVE", "PAUSED").contains(exercise.state())
            && Objects.equals(control.credentialHash(), IdentitySecrets.digest(candidate));
        return new ControlResponse(exerciseId, operationId, state, control == null ? "0" : Long.toString(control.generation()),
            control != null && control.credentialHash() != null, delivered, delivered ? candidate : null);
    }

    @Transactional(readOnly = true)
    public WorkspaceOperationResponse result(String session, String id) {
        String user = identity.authorization(session).userId();
        WorkspaceOperationEntity operation = exercises.operation(id);
        if (operation == null || !operation.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(operation.exerciseId())) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        WorkspaceMutationEntity mutation = writes.mutation(id);
        return new WorkspaceOperationResponse(id, operation.exerciseId(), operation.state(), "/api/operations/" + id,
            mutation == null || mutation.revision() == null ? null : mutation.revision().toString(),
            mutation == null ? null : mutation.changed(), mutation == null ? null : mutation.failureCode());
    }

    private WorkspaceOperationEntity operation(ExerciseEntity exercise, String kind, String key, String fingerprint) {
        WorkspaceOperationEntity operation = new WorkspaceOperationEntity(UUID.randomUUID().toString(), exercise.userId(), exercise.id(),
            exercise.workspaceInstanceId(), kind, exercise.id(), key, fingerprint, "PENDING", clock.instant());
        exercises.insertOperation(operation);
        return operation;
    }

    private ExerciseEntity owned(String user, String id) {
        ExerciseEntity exercise = exercises.find(id);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        return exercise;
    }

    private void writable(ExerciseEntity exercise, boolean activeOnly) {
        if (!exercise.state().equals("ACTIVE") && (activeOnly || !List.of("READY", "PAUSED").contains(exercise.state()))) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (exercise.deadlineAt() != null && !clock.instant().isBefore(exercise.deadlineAt())) throw new BusinessException(WorkspaceErrorCode.EXERCISE_EXPIRED);
    }

    private static void idle(WorkspaceControlEntity control) {
        if (control != null && (control.transitionId() != null || control.fenceId() != null)) throw new BusinessException(WorkspaceErrorCode.CONTROL_TRANSITION_PENDING);
    }
    private static void credential(WorkspaceControlEntity control, String credential) {
        String hash = IdentitySecrets.digest(credential);
        if (control == null || hash == null || !hash.equals(control.credentialHash())) throw new BusinessException(WorkspaceErrorCode.STALE_CONTROL);
    }
    private static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
    }
}
