package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalChangeResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalModeRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalModeResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotApprovalChangeEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotApprovalModeEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotApprovalModeRepository;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotApprovalModeStore {
    private final CopilotApprovalModeRepository modes;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ExerciseTransactionService exercises;
    private final WorkspaceCoordinationStore workspace;
    private final WorkspaceAiOperationService operations;
    private final Clock clock;

    public CopilotApprovalModeStore(CopilotApprovalModeRepository modes, IdentityTransactionService identity, IdentitySecrets secrets,
        ExerciseTransactionService exercises, WorkspaceCoordinationStore workspace, WorkspaceAiOperationService operations, Clock clock) {
        this.modes = modes; this.identity = identity; this.secrets = secrets; this.exercises = exercises;
        this.workspace = workspace; this.operations = operations; this.clock = clock;
    }

    @Transactional
    public CopilotApprovalModeResponse current(String session, String exercise) {
        owner(session, exercise);
        var mode = modes.current(exercise);
        return new CopilotApprovalModeResponse(exercise, mode == null ? "ASK" : mode.mode(), mode == null ? "0" : Long.toString(mode.version()), modes.history(exercise));
    }

    @Transactional
    public CopilotApprovalChangeResponse replay(String session, String exercise, String key, CopilotApprovalModeRequest input) {
        owner(session, exercise); validate(key, input);
        return receipt(exercise, key, fingerprint(input));
    }

    @Transactional
    public CopilotApprovalChangeResponse change(String session, String exercise, String credential, String key,
        CopilotApprovalModeRequest input, StorageMeasurement measurement) {
        var grant = identity.authorizeMutation(session);
        owner(session, exercise);
        long expected = validate(key, input);
        String fingerprint = fingerprint(input);
        var previous = receipt(exercise, key, fingerprint);
        if (previous != null) return previous;
        workspace.checkControl(exercise, IdentitySecrets.digest(credential));
        var active = exercises.find(session, exercise);
        if (!"ACTIVE".equals(active.state()) || active.deadlineAt() == null || !clock.instant().isBefore(active.deadlineAt())) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        }
        var current = modes.current(exercise);
        long version = current == null ? 0 : current.version();
        if (version != expected) throw new BusinessException(CopilotErrorCode.COPILOT_APPROVAL_MODE_CONFLICT);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_APPROVAL_MODE", measurement, 4096);
        var change = new CopilotApprovalChangeEntity(id, exercise, grant.userId(), grant.authVersion(), current == null ? "ASK" : current.mode(),
            input.mode(), version + 1, key, fingerprint, clock.instant());
        modes.audit(change);
        modes.select(new CopilotApprovalModeEntity(exercise, input.mode(), version + 1, clock.instant()));
        operations.settle(exercise, id, true);
        return response(change);
    }

    private void owner(String session, String exercise) {
        var grant = identity.authorizePrivateDelivery(session);
        if (!"USER".equals(grant.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        exercises.find(session, exercise);
    }

    private long validate(String key, CopilotApprovalModeRequest input) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || input == null || !("ASK".equals(input.mode()) || "AUTO".equals(input.mode()))
            || input.expectedVersion() == null || !input.expectedVersion().matches("0|[1-9][0-9]{0,18}")) {
            throw new BusinessException(CopilotErrorCode.COPILOT_APPROVAL_MODE_INVALID);
        }
        try {
            long version = Long.parseLong(input.expectedVersion());
            if (version == Long.MAX_VALUE) throw new NumberFormatException();
            return version;
        } catch (NumberFormatException invalid) { throw new BusinessException(CopilotErrorCode.COPILOT_APPROVAL_MODE_INVALID); }
    }

    private CopilotApprovalChangeResponse receipt(String exercise, String key, String fingerprint) {
        var receipt = modes.receipt(exercise, key);
        if (receipt == null) return null;
        if (!receipt.fingerprint().equals(fingerprint)) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
        return response(receipt);
    }
    private String fingerprint(CopilotApprovalModeRequest input) { return secrets.fingerprint(input.mode() + "\n" + input.expectedVersion()); }
    private CopilotApprovalChangeResponse response(CopilotApprovalChangeEntity change) {
        return new CopilotApprovalChangeResponse(change.exerciseId(), change.id(), change.previousMode(), change.mode(), Long.toString(change.version()), change.actorId(), change.createdAt());
    }
}
