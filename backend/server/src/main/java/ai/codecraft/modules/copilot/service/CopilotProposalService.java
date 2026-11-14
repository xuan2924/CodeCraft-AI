package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDetailResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalsResponse;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.codecraft.modules.copilot.model.dto.CopilotFileDiffDTO;
import java.util.List;

@Service
public class CopilotProposalService {
    private final CopilotProposalStore store;
    private final StorageCapacityService capacity;
    private final ObjectMapper json;
    public CopilotProposalService(CopilotProposalStore store, StorageCapacityService capacity, @Qualifier("workspaceJson") ObjectMapper json) {
        this.store = store; this.capacity = capacity; this.json = json;
    }
    public CopilotProposalsResponse list(String session, String task, long before) { return store.list(session, task, before); }
    public CopilotProposalResponse summary(String session, String proposal) { return store.summary(session, proposal); }
    public CopilotProposalDetailResponse detail(String session, String proposal) {
        var evidence = store.evidence(session, proposal);
        // The immutable full diff can be large; decode only after the authorization/read transaction ends.
        try {
            List<CopilotFileDiffDTO> files = json.readValue(evidence.diffPayload(), new TypeReference<>() {});
            return new CopilotProposalDetailResponse(evidence.proposal(), files);
        } catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored proposal evidence cannot be decoded"); }
    }
    public CopilotProposalResponse decide(String session, String proposal, String credential, String key, CopilotProposalDecisionRequest input) {
        var prior = store.replay(session, proposal, key, input);
        if (prior != null) return prior;
        if ("REJECT".equals(input.decision())) return store.decide(session, proposal, credential, key, input, null);
        String exercise = store.exercise(session, proposal);
        return capacity.admit(session, exercise, measured -> store.decide(session, proposal, credential, key, input, measured));
    }
}
