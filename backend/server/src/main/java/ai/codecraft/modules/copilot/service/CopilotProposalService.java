package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDetailResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalsResponse;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import org.springframework.stereotype.Service;

@Service
public class CopilotProposalService {
    private final CopilotProposalStore store;
    private final StorageCapacityService capacity;
    public CopilotProposalService(CopilotProposalStore store, StorageCapacityService capacity) { this.store = store; this.capacity = capacity; }
    public CopilotProposalsResponse list(String session, String task, long before) { return store.list(session, task, before); }
    public CopilotProposalResponse summary(String session, String proposal) { return store.summary(session, proposal); }
    public CopilotProposalDetailResponse detail(String session, String proposal) { return store.detail(session, proposal); }
    public CopilotProposalResponse decide(String session, String proposal, String credential, String key, CopilotProposalDecisionRequest input) {
        var prior = store.replay(session, proposal, key, input);
        if (prior != null) return prior;
        if ("REJECT".equals(input.decision())) return store.decide(session, proposal, credential, key, input, null);
        String exercise = store.exercise(session, proposal);
        return capacity.admit(session, exercise, measured -> store.decide(session, proposal, credential, key, input, measured));
    }
}
