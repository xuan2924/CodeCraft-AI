package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotDeliveryDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotPartialResponse;
import java.util.List;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.copilot.repository.CopilotProposalRepository;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.ExerciseModelService;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotTaskStore {
    private static final long START_CREDIT = 64 * 1024;
    private final CopilotTaskRepository tasks;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ExerciseTransactionService exercises;
    private final WorkspaceCoordinationStore control;
    private final ExerciseModelService models;
    private final WorkspaceAiOperationService operations;
    private final Clock clock;
    private final CopilotProposalRepository proposals;

    public CopilotTaskStore(CopilotTaskRepository tasks, IdentityTransactionService identity, IdentitySecrets secrets,
        ExerciseTransactionService exercises, WorkspaceCoordinationStore control, ExerciseModelService models,
        WorkspaceAiOperationService operations, Clock clock, CopilotProposalRepository proposals) {
        this.tasks = tasks; this.identity = identity; this.secrets = secrets; this.exercises = exercises;
        this.control = control; this.models = models; this.operations = operations; this.clock = clock; this.proposals = proposals;
    }

    @Transactional
    public CopilotTaskResponse accept(String session, String exercise, String credential, String key, String text,
        boolean feeAcknowledged, StorageMeasurement measurement) {
        var grant = identity.authorizeMutation(session);
        exercises.find(session, exercise);
        control.checkControl(exercise, IdentitySecrets.digest(credential));
        validateKey(key);
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > 4096 || !feeAcknowledged) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
        String fingerprint = secrets.fingerprint(text);
        var previous = tasks.receipt(exercise, key);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
            return describe(previous);
        }
        if (tasks.active(exercise)) throw new BusinessException(CopilotErrorCode.COPILOT_BUSY);
        models.resolveForCall(session, exercise);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_START", measurement, START_CREDIT);
        var task = new CopilotTaskEntity(id, exercise, grant.userId(), session, key, fingerprint, "QUEUED", null, false, null, null, clock.instant(), null);
        tasks.insert(task);
        tasks.message(new CopilotMessageEntity(0, UUID.randomUUID().toString(), id, exercise, "USER",
            ChatMessageSerializer.messageToJson(UserMessage.from(text)), clock.instant()));
        operations.settle(exercise, id, true);
        return describe(task);
    }

    @Transactional
    public CopilotTaskResponse latest(String session, String exercise) {
        identity.authorizePrivateDelivery(session);
        exercises.find(session, exercise);
        var task = tasks.latest(exercise);
        return task == null ? null : describe(task);
    }

    @Transactional
    public CopilotTaskResponse find(String session, String id) {
        return describe(authorize(session, id));
    }

    @Transactional
    public CopilotDeliveryDTO delivery(String session, String id) {
        var task = authorize(session, id);
        var partial = tasks.partial(id);
        return new CopilotDeliveryDTO(task.userId(), describe(task), Long.toString(tasks.lastSequence(task.exerciseId())), tasks.textHistory(id),
            partial == null ? new CopilotPartialResponse(null, "0", "", false) : partial);
    }

    @Transactional
    public List<CopilotMessageEntity> messages(String session, String exercise, long after) {
        identity.authorizePrivateDelivery(session);
        exercises.find(session, exercise);
        if (after < -1) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        return after == -1 ? tasks.recent(exercise) : tasks.page(exercise, after);
    }

    /** Monotone stop needs no fresh capacity; it does not cancel an independent manual test. */
    @Transactional
    public CopilotTaskResponse stop(String session, String id, String key) {
        identity.authorizeMutation(session);
        var task = authorize(session, id);
        validateKey(key);
        tasks.stop(task.id(), clock.instant());
        var pending = proposals.pending(task.id());
        if (pending != null && "APPLYING".equals(pending.state())) {
            var outcome = operations.mutationOutcome(task.exerciseId(), pending.operationId());
            if ("PENDING".equals(outcome.state()) || "PENDING_RECONCILIATION".equals(outcome.state())) control.reconcile(task.exerciseId());
        }
        return describe(tasks.find(id));
    }

    private CopilotTaskEntity authorize(String session, String id) {
        var grant = identity.authorizePrivateDelivery(session);
        var task = tasks.find(id);
        if (task == null || !task.userId().equals(grant.userId())) throw new BusinessException(CopilotErrorCode.COPILOT_NOT_FOUND);
        exercises.find(session, task.exerciseId());
        return task;
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
    }

    private CopilotTaskResponse describe(CopilotTaskEntity task) {
        return new CopilotTaskResponse(task.id(), task.exerciseId(), task.state(), task.reason(), task.stopRequested(), task.createdAt(), task.completedAt());
    }
}
