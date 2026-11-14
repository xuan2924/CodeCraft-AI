package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotToolExecutionDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CopilotToolExecutor {
    private final CopilotReadTools reads;
    private final CopilotProposalPreparation preparation;
    private final CopilotProposalStore proposals;
    private final ObjectMapper json;
    public CopilotToolExecutor(CopilotReadTools reads, CopilotProposalPreparation preparation, CopilotProposalStore proposals, ObjectMapper json) {
        this.reads = reads; this.preparation = preparation; this.proposals = proposals; this.json = json;
    }
    public CopilotToolExecutionDTO execute(CopilotTaskEntity task, String worker, CopilotToolEntity tool) throws JsonProcessingException {
        if (!"propose_changes".equals(tool.name())) {
            return new CopilotToolExecutionDTO(false, json.writeValueAsString(reads.execute(task.sessionHash(), task.exerciseId(), tool.name(), tool.arguments())));
        }
        try {
            var prepared = preparation.prepare(task.sessionHash(), task.exerciseId(), tool.arguments());
            if (proposals.register(task.id(), worker, tool.id(), prepared)) return new CopilotToolExecutionDTO(true, null);
            return new CopilotToolExecutionDTO(false, json.writeValueAsString(Map.of("code", "PROPOSAL_PREVIOUSLY_REJECTED")));
        } catch (BusinessException rejected) {
            return new CopilotToolExecutionDTO(false, json.writeValueAsString(Map.of("code", rejected.errorCode().code())));
        }
    }
}
