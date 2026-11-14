package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.repository.CopilotProposalRepository;
import ai.codecraft.modules.workspace.model.dto.FileBatchRequest;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import ai.codecraft.modules.workspace.service.WorkspaceBatchCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Runs on the existing bounded worker pool, never inside a scheduler/SQL transaction. */
@Service
public class CopilotAutoApproval {
    private final CopilotProposalStore store;
    private final CopilotProposalRepository proposals;
    private final StorageCapacityService capacity;
    private final WorkspaceBatchCodec codec;
    private final ObjectMapper json;
    public CopilotAutoApproval(CopilotProposalStore store, CopilotProposalRepository proposals, StorageCapacityService capacity,
        WorkspaceBatchCodec codec, @Qualifier("workspaceJson") ObjectMapper json) {
        this.store = store; this.proposals = proposals; this.capacity = capacity; this.codec = codec; this.json = json;
    }
    public List<String> pending() { return proposals.automaticPending(); }
    public void apply(String taskId) {
        var source = store.autoCandidate(taskId);
        if (source == null) return;
        try {
            var batch = json.readValue(source.batchPayload(), FileBatchRequest.class);
            String payload = codec.encode(batch);
            if (!payload.equals(source.batchPayload())) throw new IllegalStateException("Automatic proposal is not canonical");
            capacity.admit(source.sessionHash(), source.exerciseId(), measured -> store.applyAuto(source, payload, measured));
        } catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored automatic proposal cannot be decoded", invalid); }
        catch (BusinessException rejected) {
            // A meter/coordination outage is not a rejected tool result and must not trigger another paid model round.
            if (rejected.errorCode().status() >= 500) throw rejected;
            store.failAuto(source.taskId(), source.proposalId(), rejected.errorCode().code());
        }
    }
}
