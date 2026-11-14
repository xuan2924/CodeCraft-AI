package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotApprovalChangeResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalModeRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotApprovalModeResponse;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import org.springframework.stereotype.Service;

@Service
public class CopilotApprovalModeService {
    private final CopilotApprovalModeStore store;
    private final StorageCapacityService capacity;
    public CopilotApprovalModeService(CopilotApprovalModeStore store, StorageCapacityService capacity) {
        this.store = store; this.capacity = capacity;
    }
    public CopilotApprovalModeResponse current(String session, String exercise) { return store.current(session, exercise); }
    public CopilotApprovalChangeResponse change(String session, String exercise, String credential, String key, CopilotApprovalModeRequest input) {
        var receipt = store.replay(session, exercise, key, input);
        return receipt != null ? receipt : capacity.admit(session, exercise, measured -> store.change(session, exercise, credential, key, input, measured));
    }
}
