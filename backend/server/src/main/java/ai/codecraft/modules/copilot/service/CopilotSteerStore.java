package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerPreparedDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotSteerResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotSteersResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotSteerEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotSteerRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotSteerStore {
    private final CopilotSteerRepository steers;
    private final CopilotTaskRepository tasks;
    private final CopilotTaskStore views;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore workspace;
    private final WorkspaceAiOperationService operations;
    private final CopilotExecutionStore execution;
    private final Clock clock;

    public CopilotSteerStore(CopilotSteerRepository steers, CopilotTaskRepository tasks, CopilotTaskStore views,
        IdentityTransactionService identity, WorkspaceCoordinationStore workspace, WorkspaceAiOperationService operations,
        CopilotExecutionStore execution, Clock clock) {
        this.steers = steers; this.tasks = tasks; this.views = views; this.identity = identity;
        this.workspace = workspace; this.operations = operations; this.execution = execution; this.clock = clock;
    }

    @Transactional
    public CopilotSteersResponse list(String session, String task, long before) {
        owner(session, task);
        if (before < 0) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        var page = steers.page(task, before);
        var items = (page.size() > 20 ? page.subList(0, 20) : page).stream().map(this::response).toList();
        return new CopilotSteersResponse(items, page.size() > 20 ? items.getLast().sequence() : null);
    }

    @Transactional
    public CopilotSteerResponse replay(String session, String task, String key, String fingerprint) {
        owner(session, task);
        return receipt(task, key, fingerprint);
    }

    @Transactional
    public CopilotSteerResponse accept(String session, String taskId, String credential, String key,
        CopilotSteerPreparedDTO input, StorageMeasurement measured) {
        var grant = identity.authorizeMutation(session);
        var task = owner(session, taskId);
        var replay = receipt(taskId, key, input.fingerprint());
        if (replay != null) return replay;
        workspace.checkControl(task.exerciseId(), IdentitySecrets.digest(credential));
        var exercise = workspace.exercise(task.exerciseId());
        var original = identity.tryAuthorizeMutation(task.sessionHash());
        if (task.stopRequested() || !Set.of("QUEUED", "RUNNING", "WAITING_APPROVAL", "WAITING_WRITE", "WAITING_TEST", "WAITING_COMMAND", "WAITING_CHILD").contains(task.state())
            || exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt())
            || original == null || !task.userId().equals(original.userId()) || !"USER".equals(original.role()) || original.passwordChangeRequired()
            || !clock.instant().isBefore(original.expiresAt())) throw new BusinessException(CopilotErrorCode.COPILOT_STEER_NOT_ACTIVE);
        if (steers.pendingCount(taskId) >= 32) throw new BusinessException(CopilotErrorCode.COPILOT_STEER_LIMIT);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, task.exerciseId(), id, "COPILOT_STEER", measured, 65536);
        steers.insert(new CopilotSteerEntity(id, 0, taskId, task.exerciseId(), grant.userId(), grant.authVersion(), key, input.fingerprint(), input.text(), input.payload(),
            null, "RECEIVED", null, null, clock.instant(), null));
        execution.steerIdle(taskId);
        return response(steers.receipt(taskId, key));
    }

    private CopilotTaskEntity owner(String session, String id) {
        var grant = identity.authorizePrivateDelivery(session);
        if (!"USER".equals(grant.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        views.find(session, id);
        return tasks.find(id);
    }
    private CopilotSteerResponse receipt(String task, String key, String fingerprint) {
        var value = steers.receipt(task, key);
        if (value == null) return null;
        if (!fingerprint.equals(value.fingerprint())) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
        return response(value);
    }
    private CopilotSteerResponse response(CopilotSteerEntity value) {
        return new CopilotSteerResponse(value.id(), Long.toString(value.sequence()), value.taskId(), value.exerciseId(), value.actorId(),
            value.text(), value.state(), value.turnId(), value.reason(), value.createdAt(), value.completedAt());
    }
}
