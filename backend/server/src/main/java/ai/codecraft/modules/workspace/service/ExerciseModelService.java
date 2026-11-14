package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationReferenceDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelInvocationDTO;
import ai.codecraft.modules.modelaccess.model.exception.ModelAccessErrorCode;
import ai.codecraft.modules.modelaccess.service.ModelCheckService;
import ai.codecraft.modules.modelaccess.service.ModelInvocationService;
import ai.codecraft.modules.workspace.model.dto.ExerciseModelResponse;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseModelRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExerciseModelService {
    private final ExerciseModelRepository selections;
    private final ExerciseRepository exercises;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore control;
    private final ModelInvocationService invocations;
    private final ModelCheckService checks;
    private final Clock clock;
    private final IdentitySecrets secrets;

    public ExerciseModelService(ExerciseModelRepository selections, ExerciseRepository exercises,
        IdentityTransactionService identity, WorkspaceCoordinationStore control, ModelInvocationService invocations,
        ModelCheckService checks, Clock clock, IdentitySecrets secrets) {
        this.selections = selections; this.exercises = exercises; this.identity = identity;
        this.control = control; this.invocations = invocations; this.checks = checks; this.clock = clock; this.secrets = secrets;
    }

    @Transactional
    public ExerciseModelResponse select(String session, String id, String credential, String key, ModelInvocationReferenceDTO reference) {
        authorize(session, id);
        control.checkControl(id, IdentitySecrets.digest(credential));
        var exercise = exercises.find(id);
        if (reference == null || reference.connectionId() == null || reference.modelConfigurationId() == null
            || !reference.connectionId().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") || !reference.modelConfigurationId().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
            || reference.connectionConfigurationVersion() < 1 || reference.modelConfigurationVersion() < 1
            || key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(ModelAccessErrorCode.MODEL_INVALID_REQUEST);
        var fingerprint = secrets.fingerprint(reference.toString());
        var previous = exercises.receipt(exercise.userId(), "SELECT_MODEL", id, key);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) throw new BusinessException(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            return current(session, id);
        }
        if (!Set.of("READY", "ACTIVE").contains(exercise.state())
            || exercise.deadlineAt() != null && !clock.instant().isBefore(exercise.deadlineAt())) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        }
        invocations.resolve(session, reference);
        selections.select(id, reference);
        exercises.insertOperation(new WorkspaceOperationEntity(UUID.randomUUID().toString(), exercise.userId(), id,
            exercise.workspaceInstanceId(), "SELECT_MODEL", id, key, fingerprint, "SUCCEEDED", clock.instant()));
        return new ExerciseModelResponse(reference, "SELECTED");
    }

    @Transactional
    public ExerciseModelResponse current(String session, String id) {
        authorize(session, id);
        var reference = selections.find(id);
        if (reference == null) return new ExerciseModelResponse(null, "SELECTION_REQUIRED");
        var resolved = invocations.tryResolve(session, reference);
        return new ExerciseModelResponse(reference, resolved.error() == null ? "SELECTED" : resolved.error().code());
    }

    /** Invoke only at a complete batch boundary; the returned credentials live only in the in-flight call. */
    @Transactional
    public ModelInvocationDTO resolveForCall(String session, String id) {
        authorize(session, id);
        var exercise = exercises.find(id);
        if (!"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt())) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        }
        var reference = selections.find(id);
        if (reference == null) throw new BusinessException(ModelAccessErrorCode.MODEL_SELECTION_REQUIRED);
        return invocations.resolve(session, reference);
    }

    @Transactional
    public void requireReady(String session, String id) {
        authorize(session, id);
        var reference = selections.find(id);
        if (reference == null) throw new BusinessException(ModelAccessErrorCode.MODEL_SELECTION_REQUIRED);
        invocations.resolve(session, reference);
        checks.requireValid(session, reference);
    }

    private void authorize(String session, String id) {
        var grant = identity.authorizeMutation(session);
        var exercise = exercises.find(id);
        if (exercise == null || !exercise.userId().equals(grant.userId())) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(id)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
    }
}
