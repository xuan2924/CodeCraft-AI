package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotBtwRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotBtwResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotTaskResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotBtwRepository;
import ai.codecraft.modules.copilot.repository.CopilotConversationRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.service.WorkspaceAiOperationService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotBtwStore {
    private final CopilotBtwRepository sides;
    private final CopilotConversationRepository conversations;
    private final CopilotConversationStore ownership;
    private final CopilotTaskRepository tasks;
    private final CopilotTaskStore starts;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final WorkspaceAiOperationService operations;
    private final Clock clock;
    public CopilotBtwStore(CopilotBtwRepository sides, CopilotConversationRepository conversations, CopilotConversationStore ownership,
        CopilotTaskRepository tasks, CopilotTaskStore starts, IdentityTransactionService identity, IdentitySecrets secrets,
        WorkspaceAiOperationService operations, Clock clock) {
        this.sides = sides; this.conversations = conversations; this.ownership = ownership; this.tasks = tasks; this.starts = starts;
        this.identity = identity; this.secrets = secrets; this.operations = operations; this.clock = clock;
    }
    @Transactional
    public CopilotBtwResponse find(String session, String exercise, String parent) {
        ownership.resolveMain(session, exercise, parent);
        return sides.find(parent);
    }
    @Transactional
    public List<CopilotMessageEntity> projection(String session, String exercise, String parent) {
        ownership.resolveMain(session, exercise, parent);
        return sides.projection(parent);
    }
    @Transactional
    public CopilotTaskResponse previous(String session, String exercise, String parent, String key, CopilotBtwRequest input) {
        ownership.resolveMain(session, exercise, parent); validate(key, input);
        return replay(session, exercise, parent, key, input);
    }
    @Transactional
    public CopilotTaskResponse start(String session, String exercise, String parent, String credential, String key, CopilotBtwRequest input,
        String projection, StorageMeasurement measurement) {
        identity.authorizeMutation(session); ownership.resolveMain(session, exercise, parent); validate(key, input);
        var previous = replay(session, exercise, parent, key, input);
        if (previous != null) return previous;
        var side = sides.find(parent);
        if (side == null) {
            String id = UUID.randomUUID().toString();
            operations.reserve(session, exercise, id, "COPILOT_BTW", measurement, 4096);
            conversations.insert(id, exercise, "btw:" + id, clock.instant());
            sides.insert(parent, id, exercise, clock.instant());
            operations.settle(exercise, id, true);
            side = sides.find(parent);
        }
        return starts.acceptBtw(session, exercise, credential, key, input.text(), input.feeAcknowledged(), side.conversationId(), projection, measurement);
    }
    private CopilotTaskResponse replay(String session, String exercise, String parent, String key, CopilotBtwRequest input) {
        var previous = tasks.receipt(exercise, key);
        if (previous == null) return null;
        var side = sides.find(parent);
        if (side == null || !"BTW".equals(previous.lane()) || !side.conversationId().equals(previous.conversationId())
            || !previous.fingerprint().equals(secrets.fingerprint(input.text()))) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
        return starts.find(session, previous.id());
    }
    private void validate(String key, CopilotBtwRequest input) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || input == null || !input.feeAcknowledged()
            || input.text() == null || input.text().isBlank() || input.text().indexOf('\0') >= 0 || CopilotContextPlanner.bytes(input.text()) > 4096) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        }
    }
}
