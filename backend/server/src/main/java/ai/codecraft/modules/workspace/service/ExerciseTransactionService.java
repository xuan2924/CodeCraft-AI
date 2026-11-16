package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.platformagent.service.PlatformBindingService;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.workspace.model.dto.PreparationAuthorizationDTO;
import ai.codecraft.modules.workspace.config.RetentionProperties;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExerciseTransactionService {
    private final ExerciseRepository repository;
    private final IdentityTransactionService identity;
    private final ProblemTransactionService problems;
    private final Clock clock;
    private final IdentitySecrets secrets;
    private final WorkspaceCoordinationStore coordination;
    private final RetentionProperties retention;
    private final StorageCapacityStore capacity;
    private final ExerciseModelService models;
    private final PlatformBindingService platform;

    public ExerciseTransactionService(ExerciseRepository repository, IdentityTransactionService identity,
                                      ProblemTransactionService problems, Clock clock, IdentitySecrets secrets, WorkspaceCoordinationStore coordination, RetentionProperties retention, StorageCapacityStore capacity, ExerciseModelService models, PlatformBindingService platform) {
        this.repository = repository;
        this.identity = identity;
        this.problems = problems;
        this.clock = clock;
        this.secrets = secrets;
        this.coordination = coordination;
        this.retention = retention;
        this.capacity = capacity;
        this.models = models;
        this.platform = platform;
    }

    @Transactional
    public WorkspaceOperationEntity create(String session, String version, String key) {
        SessionAuthorizationDTO authorization = identity.authorizeMutation(session);
        String user = authorization.userId();
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || version == null) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        }
        String fingerprint = secrets.fingerprint(version);
        WorkspaceOperationEntity previous = repository.receipt(user, "PREPARE", user, key);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            if (repository.deleted(previous.exerciseId())) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
            return previous;
        }
        if (repository.current(user) != null) throw new BusinessException(WorkspaceErrorCode.ACTIVE_EXERCISE_EXISTS);
        if (repository.retainedHistory(user) >= retention.historyLimit()) throw new BusinessException(WorkspaceErrorCode.HISTORY_LIMIT_REACHED);
        problems.requireAvailable(version);
        String exercise = UUID.randomUUID().toString();
        String instance = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        repository.insert(new ExerciseEntity(exercise, user, version, instance, operation, "PREPARING", null, "[]",
            1, clock.instant(), null, null, null, null, null));
        platform.bind(exercise);
        repository.retention(exercise, Math.multiplyExact((long) retention.days(), 86400));
        repository.bindPreparationSession(exercise, session, authorization.expiresAt());
        WorkspaceOperationEntity accepted = new WorkspaceOperationEntity(operation, user, exercise, instance,
            "PREPARE", user, key, fingerprint, "PENDING", clock.instant());
        capacity.initialize(accepted);
        repository.insertOperation(accepted);
        return accepted;
    }

    @Transactional
    public ExerciseEntity start(String session, String id, String key, int durationSeconds, String credential) {
        String user = identity.authorizeMutation(session).userId();
        ExerciseEntity exercise = repository.find(id);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        if (repository.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        coordination.checkControl(id, IdentitySecrets.digest(credential));
        if (repository.receipt(user, "START", id, key) != null) return exercise;
        if (!exercise.state().equals("READY")) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        models.requireReady(session, id);
        Instant started = clock.instant();
        if (repository.start(id, started, started.plusSeconds(durationSeconds)) != 1) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        }
        repository.insertOperation(new WorkspaceOperationEntity(UUID.randomUUID().toString(), user, id,
            exercise.workspaceInstanceId(), "START", id, key, secrets.fingerprint("START"), "SUCCEEDED", started));
        return repository.find(id);
    }

    @Transactional(readOnly = true)
    public WorkspaceOperationEntity existingRetry(String session, String id, String key) {
        ExerciseEntity exercise = find(session, id);
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        return repository.receipt(exercise.userId(), "PREPARATION_RETRY", id, key);
    }

    @Transactional
    public WorkspaceOperationEntity retry(String session, String id, String key, StorageMeasurement measurement) {
        SessionAuthorizationDTO authorization = identity.authorizeMutation(session);
        String user = authorization.userId();
        ExerciseEntity exercise = repository.find(id);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        if (repository.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        WorkspaceOperationEntity previous = repository.receipt(user, "PREPARATION_RETRY", id, key);
        if (previous != null) return previous;
        String instance = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        // PREPARATION_FAILED is only recorded after a durable Go cancellation receipt.
        if (repository.retry(id, instance, operationId) != 1) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        repository.bindPreparationSession(id, session, authorization.expiresAt());
        WorkspaceOperationEntity operation = new WorkspaceOperationEntity(operationId, user, id,
            instance, "PREPARATION_RETRY", id, key, secrets.fingerprint("PREPARATION_RETRY"), "PENDING", clock.instant());
        capacity.registerInstance(id, instance);
        capacity.admit(operation, measurement, StorageCapacityStore.PREPARATION_GROWTH);
        repository.insertOperation(operation);
        return operation;
    }

    @Transactional
    public WorkspaceOperationEntity abandon(String session, String id, String key) {
        String user = identity.authorizeMutation(session).userId();
        ExerciseEntity exercise = repository.find(id);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        if (repository.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        WorkspaceOperationEntity previous = repository.receipt(user, "ABANDON", id, key);
        if (previous != null) return previous;
        if (repository.abandon(id) != 1) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        repository.endedAt(id, clock.instant());
        WorkspaceOperationEntity operation = new WorkspaceOperationEntity(UUID.randomUUID().toString(), user, id,
            exercise.workspaceInstanceId(), "ABANDON", id, key, secrets.fingerprint("ABANDON"), "PENDING", clock.instant());
        repository.insertOperation(operation);
        coordination.fenceForAbandon(id, operation.id());
        return operation;
    }

    @Transactional(readOnly = true)
    public ExerciseEntity find(String session, String id) {
        String user = identity.authorization(session).userId();
        ExerciseEntity exercise = repository.find(id);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (repository.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        return exercise;
    }

    @Transactional(readOnly = true)
    public ExerciseEntity current(String session) {
        ExerciseEntity exercise = repository.current(identity.authorization(session).userId());
        if (exercise == null) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        return exercise;
    }

    @Transactional(readOnly = true)
    public WorkspaceOperationEntity operation(String session, String id) {
        String user = identity.authorization(session).userId();
        WorkspaceOperationEntity operation = repository.operation(id);
        if (operation == null || !operation.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (repository.deleted(operation.exerciseId())) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        return operation;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceOperationEntity> pending() { return repository.pending(); }

    @Transactional
    public void attempted(String id) { repository.attempted(id); }

    @Transactional(readOnly = true)
    public ExerciseEntity preparation(String id) { return repository.find(id); }

    @Transactional
    public void prepared(WorkspaceOperationEntity operation, long revision, String paths) {
        if (checkPreparationAuthorization(operation) == null) throw new IdentityFailure(IdentityErrorCode.UNAUTHENTICATED);
        if (repository.prepared(operation.exerciseId(), operation.workspaceInstanceId(), operation.id(), revision, paths) == 1) {
            repository.operationState(operation.id(), "SUCCEEDED");
        }
    }

    @Transactional
    public void preparationFailed(WorkspaceOperationEntity operation) {
        if (repository.preparationFailed(operation.exerciseId(), operation.id()) == 1) {
            repository.operationState(operation.id(), "REJECTED");
        }
    }

    @Transactional
    public void cancelled(WorkspaceOperationEntity operation) {
        repository.cleaned(operation.exerciseId(), operation.workspaceInstanceId());
        repository.operationState(operation.id(), "SUCCEEDED");
    }

    @Transactional
    public void failureIntent(WorkspaceOperationEntity operation, String reason) {
        repository.failureIntent(operation.exerciseId(), operation.id(), reason);
        repository.operationState(operation.id(), "PENDING_RECONCILIATION");
    }

    @Transactional
    public PreparationAuthorizationDTO preparationAuthorization(WorkspaceOperationEntity operation) {
        return checkPreparationAuthorization(operation);
    }

    private PreparationAuthorizationDTO checkPreparationAuthorization(WorkspaceOperationEntity operation) {
        PreparationAuthorizationDTO authority = repository.preparationAuthorization(operation.exerciseId());
        if (authority == null || authority.sessionHash() == null || authority.notAfter() == null
            || !clock.instant().isBefore(authority.notAfter())) return null;
        SessionAuthorizationDTO current = identity.tryAuthorizeMutation(authority.sessionHash());
        return current != null && current.userId().equals(operation.userId()) ? authority : null;
    }

    @Transactional
    public void unknown(String operation) { repository.operationState(operation, "PENDING_RECONCILIATION"); }
}
