package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Domain entry into the existing operation ledger and unified storage credit; never dispatches work. */
@Service
public class WorkspaceAiOperationService {
    private static final Set<String> KINDS = Set.of("COPILOT_START", "COPILOT_MODEL", "COPILOT_READ", "COPILOT_PROPOSAL", "COPILOT_TEST", "COPILOT_APPROVAL_MODE", "COPILOT_STEER");
    private final IdentityTransactionService identity;
    private final ExerciseRepository exercises;
    private final WorkspaceWriteRepository controls;
    private final StorageCapacityStore capacity;
    private final Clock clock;

    public WorkspaceAiOperationService(IdentityTransactionService identity, ExerciseRepository exercises,
        WorkspaceWriteRepository controls, StorageCapacityStore capacity, Clock clock) {
        this.identity = identity; this.exercises = exercises; this.controls = controls; this.capacity = capacity; this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(String session, String exerciseId, String operationId, String kind, StorageMeasurement measurement, long bytes) {
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unsupported AI operation kind");
        var grant = identity.authorizeMutation(session);
        var exercise = exercises.find(exerciseId);
        if (exercise == null || !exercise.userId().equals(grant.userId())) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(exerciseId)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        var control = controls.lock(exerciseId);
        if (!"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt())) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        }
        if (control == null || !"OPEN".equals(control.gateState()) || control.transitionId() != null || control.fenceId() != null) {
            throw new BusinessException(WorkspaceErrorCode.CONTROL_TRANSITION_PENDING);
        }
        var operation = new WorkspaceOperationEntity(operationId, grant.userId(), exerciseId, exercise.workspaceInstanceId(), kind,
            exerciseId, operationId, operationId, "PENDING", clock.instant());
        capacity.admit(operation, measurement, bytes);
        exercises.insertOperation(operation);
    }

    /** Internal receipt lookup for already admitted AI file writes; it cannot dispatch or change authorization. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkspaceOperationResponse mutationOutcome(String exerciseId, String operationId) {
        var exercise = exercises.find(exerciseId);
        if (exercise == null) throw new IllegalStateException("Mutation exercise is missing");
        identity.lockResourceOwner(exercise.userId());
        var operation = exercises.operation(operationId);
        if (operation == null || !exerciseId.equals(operation.exerciseId()) || !"FILE_BATCH".equals(operation.kind())) {
            throw new IllegalStateException("Mutation operation identity mismatch");
        }
        var mutation = controls.mutation(operationId);
        if (mutation == null) throw new IllegalStateException("Mutation evidence is missing");
        return new WorkspaceOperationResponse(operationId, exerciseId, operation.state(), "/api/operations/" + operationId,
            mutation.revision() == null ? null : mutation.revision().toString(), mutation.changed(), mutation.failureCode());
    }

    /** Internal convergence records results even after session revocation; it never grants a new side effect. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(String exerciseId, String operationId, boolean successful) {
        var exercise = exercises.find(exerciseId);
        if (exercise == null) return;
        identity.lockResourceOwner(exercise.userId());
        var operation = exercises.operation(operationId);
        if (operation == null || !operation.exerciseId().equals(exerciseId) || !KINDS.contains(operation.kind())) {
            throw new IllegalStateException("AI operation identity mismatch");
        }
        if ("PENDING".equals(operation.state())) exercises.operationState(operationId, successful ? "SUCCEEDED" : "REJECTED");
    }
}
