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
import ai.codecraft.modules.copilot.repository.CopilotSteerRepository;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.copilot.repository.CopilotProposalRepository;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.skills.service.SkillSnapshots;
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
    private final CopilotTestLifecycle tests;
    private final CopilotCommandLifecycle commands;
    private final CopilotSteerRepository steers;
    private final CopilotCompactionRepository contexts;
    private final SkillSnapshots skills;
    private final CopilotConversationStore conversations;

    public CopilotTaskStore(CopilotTaskRepository tasks, IdentityTransactionService identity, IdentitySecrets secrets,
        ExerciseTransactionService exercises, WorkspaceCoordinationStore control, ExerciseModelService models,
        WorkspaceAiOperationService operations, Clock clock, CopilotProposalRepository proposals, CopilotTestLifecycle tests, CopilotSteerRepository steers, CopilotCompactionRepository contexts, SkillSnapshots skills, CopilotConversationStore conversations, CopilotCommandLifecycle commands) {
        this.conversations = conversations; this.commands = commands;
        this.tasks = tasks; this.identity = identity; this.secrets = secrets; this.exercises = exercises;
        this.control = control; this.models = models; this.operations = operations; this.clock = clock; this.proposals = proposals; this.tests = tests; this.steers = steers; this.contexts = contexts; this.skills = skills;
    }

    @Transactional
    public CopilotTaskResponse accept(String session, String exercise, String credential, String key, String text,
        boolean feeAcknowledged, StorageMeasurement measurement) {
        return acceptInternal(session, exercise, credential, key, text, feeAcknowledged, List.of(), measurement, exercise);
    }

    @Transactional
    public CopilotTaskResponse acceptWithSkills(String session, String exercise, String credential, String key, String text,
        boolean feeAcknowledged, List<String> selected, StorageMeasurement measurement) {
        return acceptInternal(session, exercise, credential, key, text, feeAcknowledged, selected == null ? List.of() : selected, measurement, exercise);
    }

    @Transactional
    public CopilotTaskResponse acceptInConversation(String session, String exercise, String credential, String key, String text,
        boolean feeAcknowledged, List<String> selected, StorageMeasurement measurement, String conversation) {
        return acceptInternal(session, exercise, credential, key, text, feeAcknowledged,
            selected == null ? List.of() : selected, measurement, conversations.resolve(session, exercise, conversation));
    }

    private CopilotTaskResponse acceptInternal(String session, String exercise, String credential, String key, String text,
        boolean feeAcknowledged, List<String> selected, StorageMeasurement measurement, String conversation) {
        var grant = identity.authorizeMutation(session);
        exercises.find(session, exercise);
        control.checkControl(exercise, IdentitySecrets.digest(credential));
        validateKey(key);
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > 4096 || !feeAcknowledged) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
        if (selected.size() > 1000 || selected.stream().anyMatch(id -> id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
        String fingerprint = secrets.fingerprint(text);
        var previous = tasks.receipt(exercise, key);
        if (previous != null) {
            var previousSelection = skills.list(grant.userId(), previous.id()).stream().filter(item -> item.explicitlySelected()).map(item -> item.skillId()).sorted().toList();
            if (!previous.conversationId().equals(conversation) || !previous.fingerprint().equals(fingerprint) || !previousSelection.equals(selected.stream().sorted().toList())) {
                throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
            }
            return describe(previous);
        }
        if (tasks.active(exercise) || contexts.active(exercise)) throw new BusinessException(CopilotErrorCode.COPILOT_BUSY);
        models.resolveForCall(session, exercise);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_START", measurement, Math.addExact(START_CREDIT, skills.additionalBytes(grant.userId(), exercise)));
        var task = new CopilotTaskEntity(id, exercise, grant.userId(), session, key, fingerprint, "QUEUED", null, false, null, null, clock.instant(), null, conversation);
        tasks.insert(task);
        skills.pin(grant.userId(), id, exercise, selected);
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
    public CopilotTaskResponse latestInConversation(String session, String exercise, String conversation) {
        var task = tasks.latest(conversations.resolve(session, exercise, conversation));
        return task == null ? null : describe(task);
    }

    @Transactional
    public CopilotTaskResponse find(String session, String id) {
        return describe(authorize(session, id));
    }

    @Transactional
    public String projectInstructions(String session, String id) {
        authorize(session, id);
        return tasks.projectInstructions(id);
    }

    @Transactional
    public CopilotDeliveryDTO delivery(String session, String id) {
        var task = authorize(session, id);
        var partial = tasks.partial(id);
        return new CopilotDeliveryDTO(task.userId(), describe(task), Long.toString(tasks.lastSequence(task.conversationId())), tasks.textHistory(id),
            partial == null ? new CopilotPartialResponse(null, "0", "", false, "", false) : partial);
    }

    @Transactional
    public List<CopilotMessageEntity> messages(String session, String exercise, long after) {
        identity.authorizePrivateDelivery(session);
        exercises.find(session, exercise);
        if (after < -1) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        return after == -1 ? tasks.recent(exercise) : tasks.page(exercise, after);
    }

    @Transactional
    public List<CopilotMessageEntity> messagesInConversation(String session, String exercise, long after, String conversation) {
        String scope = conversations.resolve(session, exercise, conversation);
        if (after < -1) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        return after == -1 ? tasks.recent(scope) : tasks.page(scope, after);
    }

    /** Monotone stop needs no fresh capacity; it does not cancel an independent manual test. */
    @Transactional
    public CopilotTaskResponse stop(String session, String id, String key) {
        identity.authorizeMutation(session);
        var task = authorize(session, id);
        validateKey(key);
        tasks.stop(task.id(), clock.instant());
        tests.stop(task.id());
        commands.stop(task.id());
        var pending = proposals.pending(task.id());
        if (pending != null && "APPLYING".equals(pending.state())) {
            var outcome = operations.mutationOutcome(task.exerciseId(), pending.operationId());
            if ("PENDING".equals(outcome.state()) || "PENDING_RECONCILIATION".equals(outcome.state())) control.reconcile(task.exerciseId());
        }
        var stopped = tasks.find(id);
        if ("STOPPED".equals(stopped.state())) {
            for (String steer : steers.pendingIds(id)) operations.settle(task.exerciseId(), steer, false);
            steers.notApplied(id, "USER_STOP", clock.instant());
        }
        return describe(stopped);
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
        return new CopilotTaskResponse(task.id(), task.exerciseId(), task.state(), task.reason(), task.stopRequested(), task.createdAt(), task.completedAt(), task.conversationId());
    }
}
