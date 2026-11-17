package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotStartRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotReferenceSelectionDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotReferencesPreparedDTO;
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
import org.springframework.transaction.annotation.Propagation;

@Service
public class CopilotTaskStore {
    private static final long START_CREDIT = 64 * 1024;
    private final CopilotTaskRepository tasks;
    private final IdentityTransactionService identity;
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
    private final CopilotReferences references;
    private final CopilotDelegationLifecycle delegations;

    private final CopilotActivitySignals signals;

    public CopilotTaskStore(CopilotTaskRepository tasks, IdentityTransactionService identity,
        ExerciseTransactionService exercises, WorkspaceCoordinationStore control, ExerciseModelService models,
        WorkspaceAiOperationService operations, Clock clock, CopilotProposalRepository proposals, CopilotTestLifecycle tests, CopilotSteerRepository steers, CopilotCompactionRepository contexts, SkillSnapshots skills, CopilotConversationStore conversations, CopilotCommandLifecycle commands, CopilotReferences references, CopilotDelegationLifecycle delegations, CopilotActivitySignals signals) {
        this.signals = signals;
        this.conversations = conversations; this.commands = commands; this.references = references; this.delegations = delegations;
        this.tasks = tasks; this.identity = identity; this.exercises = exercises;
        this.control = control; this.models = models; this.operations = operations; this.clock = clock; this.proposals = proposals; this.tests = tests; this.steers = steers; this.contexts = contexts; this.skills = skills;
    }

    @Transactional
    public CopilotTaskResponse acceptFollowup(String session, String exercise, String controlHash, String key, String text,
        StorageMeasurement measurement, String conversation) {
        return acceptWithControlHash(session, exercise, controlHash, key, text, true, List.of(), measurement,
            conversations.resolveMain(session, exercise, conversation));
    }

    @Transactional
    public CopilotTaskResponse previousStart(String session, String exercise, String key, CopilotStartRequest input) {
        var grant = identity.authorizePrivateDelivery(session);
        String conversation = conversations.resolveMain(session, exercise, input.conversationId());
        validateStart(key, input.text(), input.feeAcknowledged(), input.skillIds());
        String fingerprint = references.requestFingerprint(input.text(), input.references());
        var previous = tasks.receipt(exercise, key);
        if (previous == null) return null;
        checkReplay(previous, conversation, fingerprint, grant.userId(), input.skillIds());
        return describe(previous);
    }

    @Transactional
    public CopilotTaskResponse acceptPrepared(String session, String exercise, String credential, String key, CopilotStartRequest input,
        CopilotReferencesPreparedDTO prepared, StorageMeasurement measurement) {
        return acceptWithReferences(session, exercise, IdentitySecrets.digest(credential), key, input.text(), input.feeAcknowledged(), input.skillIds(),
            measurement, conversations.resolveMain(session, exercise, input.conversationId()), input.references(), prepared, "MAIN", "");
    }

    private CopilotTaskResponse acceptWithControlHash(String session, String exercise, String controlHash, String key, String text,
        boolean feeAcknowledged, List<String> selected, StorageMeasurement measurement, String conversation) {
        return acceptWithReferences(session, exercise, controlHash, key, text, feeAcknowledged, selected, measurement, conversation,
            List.of(), new CopilotReferencesPreparedDTO(List.of(), ""), "MAIN", "");
    }

    @Transactional
    public CopilotTaskResponse acceptBtw(String session, String exercise, String credential, String key, String text, boolean fee,
        String conversation, String projection, StorageMeasurement measurement) {
        String resolved = conversations.resolve(session, exercise, conversation);
        if (!conversations.isBtw(resolved)) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        return acceptWithReferences(session, exercise, IdentitySecrets.digest(credential), key, text, fee, List.of(), measurement,
            resolved, List.of(), new CopilotReferencesPreparedDTO(List.of(), ""), "BTW", projection);
    }

    /** Admission is called only inside the owner-locked, authorized original tool transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CopilotTaskResponse acceptDelegated(CopilotTaskEntity parent, String tool, String conversation, String goal,
        String projection, StorageMeasurement measurement) {
        return acceptWithReferences(parent.sessionHash(), parent.exerciseId(), null, "delegation:" + tool, goal, true, List.of(),
            measurement, conversation, List.of(), new CopilotReferencesPreparedDTO(List.of(), ""), "CHILD", projection);
    }

    private CopilotTaskResponse acceptWithReferences(String session, String exercise, String controlHash, String key, String text,
        boolean feeAcknowledged, List<String> selected, StorageMeasurement measurement, String conversation,
        List<CopilotReferenceSelectionDTO> selections, CopilotReferencesPreparedDTO prepared, String lane, String projection) {
        var grant = identity.authorizeMutation(session);
        exercises.find(session, exercise);
        if (!"CHILD".equals(lane)) control.checkControl(exercise, controlHash);
        validateStart(key, text, feeAcknowledged, selected);
        String fingerprint = references.requestFingerprint(text, selections);
        var previous = tasks.receipt(exercise, key);
        if (previous != null) {
            checkReplay(previous, conversation, fingerprint, grant.userId(), selected);
            return describe(previous);
        }
        if (tasks.activeLane(exercise, lane) || ("MAIN".equals(lane) ? contexts.active(exercise) : contexts.activeInConversation(conversation))) throw new BusinessException(CopilotErrorCode.COPILOT_BUSY);
        models.resolveForCall(session, exercise);
        references.validateAdmission(session, exercise, prepared);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_START", measurement, Math.addExact(START_CREDIT + 2L * (prepared.payload() + projection).getBytes(StandardCharsets.UTF_8).length, skills.additionalBytes(grant.userId(), exercise)));
        var task = new CopilotTaskEntity(id, exercise, grant.userId(), session, key, fingerprint, "QUEUED", null, false, null, null, clock.instant(), null, conversation, lane);
        tasks.insert(task);
        signals.changed(id);
        tasks.referenceCount(id, prepared.pages().size());
        skills.pin(grant.userId(), id, exercise, selected);
        tasks.message(new CopilotMessageEntity(0, UUID.randomUUID().toString(), id, exercise, "USER",
            ChatMessageSerializer.messageToJson(UserMessage.from(text + prepared.payload() + projection)), clock.instant()));
        operations.settle(exercise, id, true);
        return describe(task);
    }

    private void validateStart(String key, String text, boolean fee, List<String> selected) {
        validateKey(key);
        if (text == null || text.isBlank() || text.indexOf('\0') >= 0 || text.getBytes(StandardCharsets.UTF_8).length > 4096 || !fee
            || selected.size() > 1000 || selected.stream().anyMatch(id -> id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
    }
    private void checkReplay(CopilotTaskEntity previous, String conversation, String fingerprint, String owner, List<String> selected) {
        var pinned = skills.list(owner, previous.id()).stream().filter(item -> item.explicitlySelected()).map(item -> item.skillId()).sorted().toList();
        if (!previous.conversationId().equals(conversation) || !previous.fingerprint().equals(fingerprint) || !pinned.equals(selected.stream().sorted().toList())) {
            throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
        }
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
        signals.changed(id);
        tests.stop(task.id());
        commands.stop(task.id());
        delegations.stop(task.id());
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
