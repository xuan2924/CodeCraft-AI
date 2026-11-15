package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotConversationResponse;
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
    public CopilotConversationResponse create(String session, String exercise, String credential, String key, StorageMeasurement measurement) {
        identity.authorizeMutation(session);
        var owner = exercises.find(session, exercise);
        workspace.checkControl(exercise, IdentitySecrets.digest(credential));
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || "initial".equals(key)) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
        var previous = conversations.receipt(exercise, key);
        if (previous != null) return previous;
        if (conversations.count(exercise) >= MAX_CONVERSATIONS) throw new BusinessException(CopilotErrorCode.COPILOT_CONVERSATION_LIMIT);
        String id = UUID.randomUUID().toString();
        operations.reserve(session, exercise, id, "COPILOT_CONVERSATION", measurement, CREATION_CREDIT);
        conversations.ensureInitial(exercise, owner.createdAt());
        conversations.insert(id, exercise, key, clock.instant());
        operations.settle(exercise, id, true);
        return conversations.find(exercise, id);
    }
}
