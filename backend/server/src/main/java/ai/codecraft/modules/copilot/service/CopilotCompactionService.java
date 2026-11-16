package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotCompactionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotContextResponse;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import org.springframework.stereotype.Service;

@Service
public class CopilotCompactionService {
    private final CopilotCompactionStore store;
    private final StorageCapacityService capacity;
    public CopilotCompactionService(CopilotCompactionStore store, StorageCapacityService capacity) {
        this.store = store; this.capacity = capacity;
    }
    public CopilotContextResponse currentInConversation(String session, String exercise, String conversation) {
        return store.currentInConversation(session, exercise, conversation);
    }
    public CopilotCompactionResponse accept(String session, String exercise, String credential, String key, CopilotCompactionRequest request) {
        var previous = store.replay(session, exercise, key, request);
        return previous != null ? previous : capacity.admit(session, exercise, measured -> store.accept(session, exercise, credential, key, request, measured));
    }
    public CopilotCompactionResponse cancel(String session, String exercise, String id) { return store.cancel(session, exercise, id); }
}
