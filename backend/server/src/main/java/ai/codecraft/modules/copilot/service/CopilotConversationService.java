package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotConversationResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotForkRequest;
import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CopilotConversationService {
    private final CopilotConversationStore store;
    private final StorageCapacityService capacity;
    private final CopilotForkBoundary boundary;
    public CopilotConversationService(CopilotConversationStore store, StorageCapacityService capacity, CopilotForkBoundary boundary) {
        this.store = store; this.capacity = capacity; this.boundary = boundary;
    }
    public CopilotConversationResponse fork(String session, String exercise, String credential, String key, CopilotForkRequest input) {
        if (input == null || input.sourceConversationId() == null || input.throughMessageId() == null) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        String source = input.sourceConversationId().toString();
        String message = input.throughMessageId().toString();
        var previous = store.replayFork(session, exercise, key, source, message);
        if (previous != null) return previous;
        var prepared = store.prepareFork(session, exercise, source, message);
        boundary.validate(prepared);
        return capacity.admit(session, exercise, measurement -> store.fork(session, exercise, credential, key, prepared, measurement));
    }
    public List<CopilotConversationResponse> list(String session, String exercise) { return store.list(session, exercise); }
    public CopilotConversationResponse create(String session, String exercise, String credential, String key) {
        var previous = store.replay(session, exercise, key);
        if (previous != null) return previous;
        return capacity.admit(session, exercise, measurement -> store.create(session, exercise, credential, key, measurement));
    }
}
