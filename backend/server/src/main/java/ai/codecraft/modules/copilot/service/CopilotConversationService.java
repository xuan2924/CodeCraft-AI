package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotConversationResponse;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CopilotConversationService {
    private final CopilotConversationStore store;
    private final StorageCapacityService capacity;
    public CopilotConversationService(CopilotConversationStore store, StorageCapacityService capacity) {
        this.store = store; this.capacity = capacity;
    }
    public List<CopilotConversationResponse> list(String session, String exercise) { return store.list(session, exercise); }
    public CopilotConversationResponse create(String session, String exercise, String credential, String key) {
        var previous = store.replay(session, exercise, key);
        if (previous != null) return previous;
        return capacity.admit(session, exercise, measurement -> store.create(session, exercise, credential, key, measurement));
    }
}
