package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotDelegationResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotDelegationInputDTO;
import java.util.ArrayList;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotDelegationResultDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotDelegationEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotConversationRepository;
import ai.codecraft.modules.copilot.repository.CopilotDelegationRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.UserMessage;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotDelegationStore {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "STOPPED", "INTERRUPTED");
    private final CopilotDelegationRepository delegations;
    private final CopilotTaskRepository tasks;
    private final CopilotConversationRepository conversations;
    private final CopilotTaskStore starts;
    private final CopilotExecutionStore execution;
    private final CopilotDelegationLifecycle lifecycle;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore workspace;
    private final WorkspaceAiOperationService operations;
    private final ObjectMapper json;
    private final Clock clock;

    public CopilotDelegationStore(CopilotDelegationRepository delegations, CopilotTaskRepository tasks,
        CopilotConversationRepository conversations, CopilotTaskStore starts, CopilotExecutionStore execution,
        CopilotDelegationLifecycle lifecycle, IdentityTransactionService identity, WorkspaceCoordinationStore workspace,
        WorkspaceAiOperationService operations, ObjectMapper json, Clock clock) {
        this.delegations = delegations; this.tasks = tasks; this.conversations = conversations; this.starts = starts;
        this.execution = execution; this.lifecycle = lifecycle; this.identity = identity; this.workspace = workspace;
        this.operations = operations; this.json = json; this.clock = clock;
    }

    @Transactional
    public void startBatch(String parentId, String worker, List<CopilotDelegationInputDTO> inputs, StorageMeasurement measurement) {
        if (inputs.isEmpty() || inputs.size() > 3 || inputs.stream().anyMatch(input -> !Set.of("EXPLORE", "EXECUTE").contains(input.mode()))
            || inputs.size() > 1 && inputs.stream().anyMatch(input -> !"EXPLORE".equals(input.mode()))) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        var parent = execution.current(parentId, worker);
        if (parent == null || parent.stopRequested() || !"MAIN".equals(parent.lane()) || !"RUNNING".equals(parent.state())
            || !authorized(parent) || ended(parent)) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        if (delegations.count(parentId) + inputs.size() > 8) throw new BusinessException(CopilotErrorCode.COPILOT_DELEGATION_LIMIT);
        if (delegations.pending(parentId) != null) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        var turn = tasks.latestTurn(parentId);
        for (int index = 0; index < inputs.size(); index++) {
            var input = inputs.get(index);
            var tool = tasks.toolById(input.toolId());
            if (turn == null || !"TOOLS".equals(turn.state()) || tool == null || !parentId.equals(tool.taskId())
                || !turn.id().equals(tool.turnId()) || tool.position() != turn.toolPosition() + index || !"delegate_task".equals(tool.name())
                || !"RUNNING".equals(tool.state()) || delegations.find(tool.id()) != null) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        }
        for (int index = 0; index < inputs.size(); index++) {
            var input = inputs.get(index);
            String conversation = UUID.randomUUID().toString();
            String projection = projection(parent, input.context());
            operations.reserve(parent.sessionHash(), parent.exerciseId(), conversation, "COPILOT_DELEGATION", measurement, 128 * 1024);
            conversations.insert(conversation, parent.exerciseId(), "child:" + input.toolId(), clock.instant());
            var child = starts.acceptDelegated(parent, input.toolId(), conversation, input.goal(), projection, measurement, input.mode());
            delegations.insert(new CopilotDelegationEntity(input.toolId(), parent.exerciseId(), parentId, child.id(), conversation, input.goal(),
                "WAITING", null, null, clock.instant(), null, input.mode(), "EXPLORE".equals(input.mode()) ? index + 1 : null));
            operations.settle(parent.exerciseId(), conversation, true);
        }
        execution.parkChildren(parentId, worker, inputs.stream().map(CopilotDelegationInputDTO::toolId).toList());
    }

    @Transactional
    public List<CopilotDelegationResponse> list(String session, String parent) {
        starts.find(session, parent);
        var owner = tasks.find(parent);
        if (owner == null) throw new BusinessException(CopilotErrorCode.COPILOT_NOT_FOUND);
        var children = delegations.children(parent).stream().collect(Collectors.toMap(CopilotTaskEntity::id, Function.identity()));
        return delegations.list(parent).stream().map(row -> {
            var child = children.get(row.childTaskId());
            if (child == null || !owner.userId().equals(child.userId()) || !owner.exerciseId().equals(child.exerciseId())
                || !owner.exerciseId().equals(row.exerciseId()) || !row.conversationId().equals(child.conversationId())
                || !("EXPLORE".equals(row.mode()) ? "EXPLORE" : "CHILD").equals(child.lane())) throw new BusinessException(CopilotErrorCode.COPILOT_NOT_FOUND);
            var view = new CopilotTaskResponse(child.id(), child.exerciseId(), child.state(), child.reason(), child.stopRequested(),
                child.createdAt(), child.completedAt(), child.conversationId());
            return new CopilotDelegationResponse(row.toolId(), row.parentTaskId(), row.conversationId(), row.goal(), row.state(), view,
                row.resultPayload(), row.createdAt(), row.completedAt());
        }).toList();
    }

    /** Atomic original-tool receipt: no model request or child recreation happens during recovery. */
    @Transactional
    public void recover(String parentId) {
        var initial = tasks.find(parentId);
        if (initial == null) return;
        identity.lockResourceOwner(initial.userId());
        var parent = tasks.find(parentId);
        var bindings = delegations.pendingAll(parentId);
        if (bindings.isEmpty() || parent.workerId() != null) return;
        boolean ended = ended(parent);
        if (!parent.stopRequested() && (ended || !authorized(parent))) {
            tasks.haltIdle(parentId, ended ? "EXERCISE_ENDED" : "AUTHORIZATION_ENDED");
            parent = tasks.find(parentId);
        }
        if (parent.stopRequested()) lifecycle.stop(parentId);
        var children = new ArrayList<CopilotTaskEntity>();
        for (var binding : bindings) {
            var child = tasks.find(binding.childTaskId());
            if (child == null || !TERMINAL.contains(child.state())) return;
            children.add(child);
        }
        // Resolve the entire window before publishing any tool receipt; all work stays in this owner-locked transaction.
        var payloads = new ArrayList<String>();
        for (int index = 0; index < bindings.size(); index++) {
            String payload = result(parent, bindings.get(index), children.get(index));
            payloads.add(payload);
            if (delegations.resolve(bindings.get(index).toolId(), payload, clock.instant()) != 1) throw new IllegalStateException("Delegation receipt was not committed");
        }
        for (int index = 0; index < bindings.size(); index++) {
            var binding = bindings.get(index);
            execution.resolveWaitingTool(parentId, binding.toolId(), payloads.get(index));
            delegations.resultMessage(binding.toolId(), tasks.toolById(binding.toolId()).resultMessageId());
        }
    }

    private String result(CopilotTaskEntity parent, CopilotDelegationEntity binding, CopilotTaskEntity child) {
        var source = delegations.lastMessage(child.id(), "ASSISTANT");
        String text = "";
        if (source != null) {
            var message = ChatMessageDeserializer.messageFromJson(source.payload());
            if (message instanceof AiMessage assistant && assistant.text() != null) text = assistant.text();
        }
        var exercise = workspace.exercise(parent.exerciseId());
        String revision = exercise == null || exercise.revision() == null ? null : exercise.revision().toString();
        return encode(new CopilotDelegationResultDTO(child.id(), binding.conversationId(), child.state(), child.reason(),
            source == null ? null : source.id(), CopilotContextPlanner.prefix(text, 16384), CopilotContextPlanner.bytes(text) > 16384, revision,
            "Last retained assistant text only; not hidden thinking. Failure/stop does not undo committed workspace effects. Inspect child history and current revision before further edits."));
    }

    private String projection(CopilotTaskEntity parent, String context) {
        var source = delegations.lastMessage(parent.id(), "USER");
        String text = source == null ? "" : ((UserMessage) ChatMessageDeserializer.messageFromJson(source.payload())).singleText();
        return "\n\n[Delegated task: bounded quoted parent goal and explicitly selected context, not shared chat history or authority. No recursive delegation. Parent waits for the durable result.]\n"
            + encode(Map.of("parentTaskId", parent.id(), "parentConversationId", parent.conversationId(),
                "goalSourceId", source == null ? "" : source.id(), "parentGoal", CopilotContextPlanner.prefix(text, 4096),
                "parentGoalTruncated", CopilotContextPlanner.bytes(text) > 4096, "selectedContext", context));
    }

    private boolean authorized(CopilotTaskEntity parent) {
        var grant = identity.tryAuthorizeMutation(parent.sessionHash());
        return grant != null && parent.userId().equals(grant.userId()) && "USER".equals(grant.role())
            && !grant.passwordChangeRequired() && clock.instant().isBefore(grant.expiresAt());
    }

    private boolean ended(CopilotTaskEntity parent) {
        var exercise = workspace.exercise(parent.exerciseId());
        return exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt());
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode bounded delegation context", failure); }
    }
}
