package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.ExerciseChallengeResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseChallengeRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExerciseChallengeStore {
    private final ExerciseChallengeRepository challenges;
    private final ExerciseRepository exercises;
    private final ProblemRepository problems;
    private final ProblemPublicationReader publications;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore coordination;
    private final ExerciseDataService data;
    private final StorageCapacityStore capacity;
    private final IdentitySecrets secrets;
    private final Clock clock;

    public ExerciseChallengeStore(ExerciseChallengeRepository challenges, ExerciseRepository exercises, ProblemRepository problems,
        ProblemPublicationReader publications, IdentityTransactionService identity, WorkspaceCoordinationStore coordination,
        ExerciseDataService data, StorageCapacityStore capacity, IdentitySecrets secrets, Clock clock) {
        this.challenges = challenges; this.exercises = exercises; this.problems = problems; this.publications = publications;
        this.identity = identity; this.coordination = coordination; this.data = data; this.capacity = capacity; this.secrets = secrets; this.clock = clock;
    }

    @Transactional
    public ExerciseChallengeResponse find(String session, String id) {
        var exercise = owned(identity.authorizePrivateDelivery(session).userId(), id);
        return describe(exercise);
    }

    @Transactional
    public boolean existing(String session, String id, String key) {
        var exercise = owned(identity.authorizeMutation(session).userId(), id);
        validateKey(key);
        return exercises.receipt(exercise.userId(), "ENTER_CHALLENGE", id, key) != null;
    }

    @Transactional
    public ExerciseChallengeResponse enter(String session, String id, String key, String credential, StorageMeasurement measurement) {
        var exercise = owned(identity.authorizeMutation(session).userId(), id);
        validateKey(key);
        if (exercises.receipt(exercise.userId(), "ENTER_CHALLENGE", id, key) != null) return describe(exercise);
        coordination.checkControl(id, IdentitySecrets.digest(credential));
        exercise = exercises.find(id);
        if (challenges.find(id) != null) return describe(exercise);
        if (!answering(exercise)) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (problems.challenge(exercise.problemVersionId()) == null) throw new BusinessException(WorkspaceErrorCode.TEST_TARGET_UNAVAILABLE);
        String passed = challenges.currentBasePass(id);
        if (passed == null) throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
        // A running model must not continue with a pre-challenge system context.
        if (!data.quiescent(id)) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        String operationId = UUID.randomUUID().toString();
        var operation = new WorkspaceOperationEntity(operationId, exercise.userId(), id, exercise.workspaceInstanceId(), "ENTER_CHALLENGE", id,
            key, secrets.fingerprint("ENTER_CHALLENGE"), "PENDING", clock.instant());
        capacity.admit(operation, measurement, 4096);
        exercises.insertOperation(operation);
        challenges.enter(id, operationId, exercise.revision(), passed, clock.instant());
        challenges.changed(id);
        exercises.operationState(operationId, "SUCCEEDED");
        return describe(exercises.find(id));
    }

    private ExerciseChallengeResponse describe(ExerciseEntity exercise) {
        var entered = challenges.find(exercise.id());
        boolean available = problems.challenge(exercise.problemVersionId()) != null;
        boolean eligible = available && entered == null && answering(exercise) && challenges.currentBasePass(exercise.id()) != null && data.quiescent(exercise.id());
        String reason = entered != null ? "ENTERED" : !available ? "NO_CHALLENGE" : !answering(exercise) ? "NOT_ACTIVE" : eligible ? "ELIGIBLE" : "CURRENT_BASE_PASS_AND_IDLE_REQUIRED";
        return new ExerciseChallengeResponse(available, entered != null, eligible, reason,
            entered == null ? null : Long.toString(entered.enteredRevision()), entered == null ? null : entered.baseTestId(),
            entered == null ? null : publications.challenge(problems.find(exercise.problemVersionId()), java.util.Set.copyOf(problems.loadedPackages())));
    }
    private boolean answering(ExerciseEntity exercise) {
        return "ACTIVE".equals(exercise.state()) && exercise.deadlineAt() != null && clock.instant().isBefore(exercise.deadlineAt());
    }
    private ExerciseEntity owned(String user, String id) {
        var exercise = exercises.find(id);
        if (exercise == null || !user.equals(exercise.userId())) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        return exercise;
    }
    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
    }
}
