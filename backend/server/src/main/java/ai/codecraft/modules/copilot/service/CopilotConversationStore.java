package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotConversationResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotForkPreparedDTO;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotConversationRepository;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotConversationStore {
    private static final int MAX_CONVERSATIONS = 100;
    private static final long CREATION_CREDIT = 4096;
    private final CopilotConversationRepository conversations;
    private final IdentityTransactionService identity;
    private final ExerciseTransactionService exercises;
    private final WorkspaceCoordinationStore workspace;
    private final WorkspaceAiOperationService operations;
    private final Clock clock;

    public CopilotConversationStore(CopilotConversationRepository conversations, IdentityTransactionService identity,
        ExerciseTransactionService exercises, WorkspaceCoordinationStore workspace, WorkspaceAiOperationService operations, Clock clock) {
        this.conversations = conversations; this.identity = identity; this.exercises = exercises;
        this.workspace = workspace; this.operations = operations; this.clock = clock;
    }

    @Transactional
    public List<CopilotConversationResponse> list(String session, String exercise) {
        identity.authorizePrivateDelivery(session);
        var owner = exercises.find(session, exercise);
        var retained = conversations.list(exercise);
        // New exercises acquire their durable initial row on the first conversation command, not on a GET.
        return retained.isEmpty() ? List.of(new CopilotConversationResponse(exercise, exercise, true, owner.createdAt())) : retained;
    }

    @Transactional
    public String resolve(String session, String exercise, String conversation) {
        identity.authorizePrivateDelivery(session);
        exercises.find(session, exercise);
        if (conversation == null || exercise.equals(conversation)) return exercise;
        if (!conversation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
        if (conversations.find(exercise, conversation) == null) throw new BusinessException(CopilotErrorCode.COPILOT_CONVERSATION_NOT_FOUND);
        return conversation;
    }

    @Transactional
    public CopilotConversationResponse replay(String session, String exercise, String key) {
        identity.authorizePrivateDelivery(session);
        exercises.find(session, exercise);
        validateKey(key);
        var receipt = conversations.receipt(exercise, key);
        if (receipt != null && receipt.forkSource() != null) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
        return receipt;
    }

    @Transactional
    public CopilotConversationResponse create(String session, String exercise, String credential, String key, StorageMeasurement measurement) {
        identity.authorizeMutation(session);
        var owner = exercises.find(session, exercise);
        workspace.checkControl(exercise, IdentitySecrets.digest(credential));
        validateKey(key);
        var previous = conversations.receipt(exercise, key);
        if (previous != null) {
            if (previous.forkSource() != null) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
            return previous;
        }
        if (conversations.count(exercise) >= MAX_CONVERSATIONS) throw new BusinessException(CopilotErrorCode.COPILOT_CONVERSATION_LIMIT);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_CONVERSATION", measurement, CREATION_CREDIT);
        conversations.ensureInitial(exercise, owner.createdAt());
        conversations.insert(id, exercise, key, clock.instant());
        operations.settle(exercise, id, true);
        return conversations.find(exercise, id);
    }

    @Transactional
    public CopilotConversationResponse replayFork(String session, String exercise, String key, String source, String message) {
        identity.authorizePrivateDelivery(session);
        exercises.find(session, exercise);
        validateKey(key);
        return forkReceipt(exercise, key, source, message);
    }

    @Transactional
    public CopilotForkPreparedDTO prepareFork(String session, String exercise, String source, String message) {
        identity.authorizePrivateDelivery(session);
        exercises.find(session, exercise);
        requireSource(exercise, source);
        var boundary = conversations.boundary(source, message);
        if (boundary == null || !exercise.equals(boundary.exerciseId())) throw new BusinessException(CopilotErrorCode.COPILOT_FORK_BOUNDARY);
        int count = conversations.forkCount(source, boundary.sequence());
        if (count > 2000 || conversations.forkBytes(source, boundary.sequence()) > 16L * 1024 * 1024) throw new BusinessException(CopilotErrorCode.COPILOT_FORK_LIMIT);
        var messages = conversations.forkMessages(source, boundary.sequence());
        if (messages.size() != count || messages.isEmpty() || !messages.getLast().id().equals(message)) throw new BusinessException(CopilotErrorCode.COPILOT_FORK_BOUNDARY);
        return new CopilotForkPreparedDTO(source, message, boundary.sequence(), messages);
    }

    @Transactional
    public CopilotConversationResponse fork(String session, String exercise, String credential, String key, CopilotForkPreparedDTO prepared, StorageMeasurement measurement) {
        identity.authorizeMutation(session);
        var owner = exercises.find(session, exercise);
        validateKey(key);
        var previous = forkReceipt(exercise, key, prepared.sourceConversationId(), prepared.throughMessageId());
        if (previous != null) return previous;
        workspace.checkControl(exercise, IdentitySecrets.digest(credential));
        requireSource(exercise, prepared.sourceConversationId());
        var boundary = conversations.boundary(prepared.sourceConversationId(), prepared.throughMessageId());
        if (boundary == null || boundary.sequence() != prepared.throughSequence()
            || conversations.forkCount(prepared.sourceConversationId(), prepared.throughSequence()) != prepared.messages().size()) {
            throw new BusinessException(CopilotErrorCode.COPILOT_FORK_BOUNDARY);
        }
        if (conversations.count(exercise) >= MAX_CONVERSATIONS) throw new BusinessException(CopilotErrorCode.COPILOT_CONVERSATION_LIMIT);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_CONVERSATION", measurement, CREATION_CREDIT + 512L * prepared.messages().size());
        conversations.ensureInitial(exercise, owner.createdAt());
        conversations.insertFork(id, exercise, key, clock.instant(), prepared.sourceConversationId(), prepared.throughMessageId());
        if (conversations.inherit(id, exercise, prepared.sourceConversationId(), prepared.throughSequence()) != prepared.messages().size()) {
            throw new IllegalStateException("Fork source changed during admission");
        }
        operations.settle(exercise, id, true);
        return conversations.find(exercise, id);
    }

    private void requireSource(String exercise, String source) {
        if (!exercise.equals(source) && conversations.find(exercise, source) == null) throw new BusinessException(CopilotErrorCode.COPILOT_CONVERSATION_NOT_FOUND);
    }
    private CopilotConversationResponse forkReceipt(String exercise, String key, String source, String message) {
        var receipt = conversations.receipt(exercise, key);
        if (receipt != null && (!source.equals(receipt.forkSource()) || !message.equals(receipt.forkMessage()))) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
        return receipt;
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || "initial".equals(key)) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
    }
}
