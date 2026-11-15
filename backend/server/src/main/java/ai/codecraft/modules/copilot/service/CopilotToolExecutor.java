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
    private final CopilotTestTools tests;
    private final CopilotEvidenceTools evidence;
    private final CopilotSkillTools skills;
    private final CopilotCommandService commands;
    private final CopilotWebTools web;
    public CopilotToolExecutor(CopilotReadTools reads, CopilotProposalPreparation preparation, CopilotProposalStore proposals, ObjectMapper json, CopilotTestTools tests, CopilotEvidenceTools evidence, CopilotSkillTools skills, CopilotCommandService commands, CopilotWebTools web) {
        this.commands = commands; this.web = web;
        this.reads = reads; this.preparation = preparation; this.proposals = proposals; this.json = json; this.tests = tests; this.evidence = evidence; this.skills = skills;
    }
    public CopilotToolExecutionDTO execute(CopilotTaskEntity task, String worker, CopilotToolEntity tool) throws JsonProcessingException, InterruptedException {
        if ("web_search".equals(tool.name()) || "web_read".equals(tool.name())) return new CopilotToolExecutionDTO(false, web.execute(task.sessionHash(), task.exerciseId(), tool.name(), tool.arguments()));
        if ("load_skill".equals(tool.name())) return new CopilotToolExecutionDTO(false, skills.execute(task.id(), worker, tool.arguments()));
        if ("run_tests".equals(tool.name())) return tests.execute(task, worker, tool);
        if ("bash".equals(tool.name())) return commands.execute(task, worker, tool);
        if ("read_evidence".equals(tool.name())) return new CopilotToolExecutionDTO(false, evidence.execute(task.id(), worker, tool.arguments()));
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
