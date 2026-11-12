package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.config.RetentionProperties;
import ai.codecraft.modules.workspace.model.dto.DeletionResponse;
import ai.codecraft.modules.workspace.model.dto.ExerciseHistoryResponse;
import ai.codecraft.modules.workspace.model.entity.DeletionEntity;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import java.util.UUID;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.DeletionRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeletionStore {
    private final DeletionRepository deletions;
    private final ExerciseRepository exercises;
    private final IdentityTransactionService identity;
    private final Clock clock;
    private final RetentionProperties properties;

    public DeletionStore(DeletionRepository deletions, ExerciseRepository exercises, IdentityTransactionService identity,
        Clock clock, RetentionProperties properties) {
        this.deletions = deletions; this.exercises = exercises; this.identity = identity;
        this.clock = clock; this.properties = properties;
    }

    @Transactional
    public DeletionResponse request(String session, String id, String key) {
        String user = identity.authorizeMutation(session).userId();
        validateKey(key);
        ExerciseEntity exercise = exercises.find(id);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (!exercise.state().equals("ENDED")) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        DeletionEntity previous = deletions.find(id);
        if (previous != null) return describe(previous);
        DeletionEntity accepted = new DeletionEntity(id, user, exercise.workspaceInstanceId(), "PENDING", clock.instant(), null, 0, clock.instant(), null, null);
        deletions.insert(accepted);
        return describe(deletions.find(id));
    }

    @Transactional(readOnly = true)
    public List<ExerciseHistoryResponse> history(String session, String after) {
        return deletions.history(identity.authorization(session).userId(), after, clock.instant().minus(Duration.ofDays(properties.progressDays())));
    }

    @Transactional(readOnly = true)
    public DeletionResponse status(String session, String id) {
        String user = identity.authorization(session).userId();
        DeletionEntity value = deletions.find(id);
        if (value == null || !value.userId().equals(user) || value.completedAt() != null
            && !clock.instant().isBefore(value.completedAt().plus(Duration.ofDays(properties.progressDays())))) {
            throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        }
        return describe(value);
    }

    @Transactional
    public DeletionResponse retry(String session, String id, String key) {
        String user = identity.authorizeMutation(session).userId();
        validateKey(key);
        DeletionEntity value = deletions.find(id);
        if (value == null || !value.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (value.completedAt() != null) return describe(value);
        if (exercises.receipt(user, "RETRY_DELETE", id, key) != null) return describe(value);
        exercises.insertOperation(new WorkspaceOperationEntity(UUID.randomUUID().toString(), user, id, value.workspaceInstanceId(),
            "RETRY_DELETE", id, key, "RETRY_DELETE", "SUCCEEDED", clock.instant()));
        deletions.retry(id, clock.instant());
        return describe(deletions.find(id));
    }

    @Transactional
    public void expire(String id) {
        ExerciseEntity exercise = exercises.find(id);
        if (exercise == null) return;
        deletions.lockOwner(exercise.userId());
        if (!exercise.state().equals("ENDED") || deletions.retainUntil(id) == null || clock.instant().isBefore(deletions.retainUntil(id))) return;
        deletions.insert(new DeletionEntity(id, exercise.userId(), exercise.workspaceInstanceId(), "PENDING", clock.instant(), null, 0, clock.instant(), null, null));
    }

    @Transactional(readOnly = true)
    public List<String> expired() { return deletions.expired(clock.instant()); }
    @Transactional(readOnly = true)
    public List<DeletionEntity> due() { return deletions.due(clock.instant()); }
    @Transactional
    public boolean claim(String id, String token) {
        return deletions.claim(id, clock.instant(), clock.instant().plusSeconds(properties.claimSeconds()), token) == 1;
    }
    @Transactional(readOnly = true)
    public List<String> workspaceInstances(String id) { return deletions.workspaceInstances(id); }
    @Transactional(readOnly = true)
    public boolean reconciled(String id) { return deletions.unresolved(id) == 0 && deletions.unclean(id) == 0; }
    @Transactional
    public void failed(DeletionEntity value, String token, String code) {
        deletions.failed(value.exerciseId(), token, clock.instant(), clock.instant().plus(properties.retryDelay(value.attempts())), code);
    }
    @Transactional
    public void complete(String id, String token) {
        // The same coordination lock orders cleanup with delayed task observations.
        deletions.lockOwner(deletions.find(id).userId());
        deletions.lockControl(id);
        if (deletions.complete(id, token, clock.instant()) != 1) return;
        deletions.clearMutations(id);
        deletions.clearEvents(id);
        deletions.clearStreams(id);
        deletions.clearStops(id);
        deletions.clearFinalTasks(id);
        deletions.clearTasks(id);
        deletions.clearFinalization(id);
        deletions.clearTransitions(id);
        deletions.clearControl(id);
        deletions.clearExercise(id);
    }
    @Transactional
    public void expireReceipts() { deletions.expireReceipts(clock.instant().minus(Duration.ofDays(properties.progressDays()))); }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
    }

    private DeletionResponse describe(DeletionEntity value) {
        return new DeletionResponse(value.exerciseId(), value.state(), value.requestedAt(), value.completedAt(), value.nextAttemptAt(), value.failureCode(),
            value.firstFailureAt() != null && !clock.instant().isBefore(value.firstFailureAt().plus(Duration.ofHours(properties.interventionHours()))));
    }
}
