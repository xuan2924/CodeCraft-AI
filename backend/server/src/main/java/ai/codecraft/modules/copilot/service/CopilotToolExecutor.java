package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.ToolOutcome;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.charset.StandardCharsets;
import ai.codecraft.modules.copilot.model.dto.CopilotToolExecutionDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CopilotToolExecutor {
    private final CopilotTaskRepository tasks;
    private final CopilotReadTools reads;
    private final CopilotProposalPreparation preparation;
    private final CopilotProposalStore proposals;
    private final ObjectMapper json;
    private final CopilotTestTools tests;
    private final CopilotEvidenceTools evidence;
    private final CopilotSkillTools skills;
    private final CopilotCommandService commands;
    private final CopilotWebTools web;
    private final CopilotReferenceService references;
    private final CopilotDelegationService delegations;
    public CopilotToolExecutor(CopilotReadTools reads, CopilotProposalPreparation preparation, CopilotProposalStore proposals, ObjectMapper json, CopilotTestTools tests, CopilotEvidenceTools evidence, CopilotSkillTools skills, CopilotCommandService commands, CopilotWebTools web, CopilotReferenceService references, CopilotDelegationService delegations, CopilotTaskRepository tasks) {
        this.tasks = tasks;
        this.commands = commands; this.web = web; this.references = references; this.delegations = delegations;
        this.reads = reads; this.preparation = preparation; this.proposals = proposals; this.json = json; this.tests = tests; this.evidence = evidence; this.skills = skills;
    }
    public ToolOutcome execute(CopilotTaskEntity task, String worker, CopilotToolEntity tool) throws JsonProcessingException, InterruptedException {
        CopilotToolExecutionDTO result;
        try {
            var definition = CopilotToolCatalog.find(tool.name());
            if (tool.arguments() == null || tool.arguments().getBytes(StandardCharsets.UTF_8).length > (definition == null ? 4096 : definition.maxArgBytes())) {
                return new ToolOutcome.Completed(ToolOutcome.Code.INVALID_ARGUMENTS, "{\"code\":\"INVALID_ARGUMENTS\"}");
            }
            if (CopilotToolPolicy.allows(task.lane(), tool.name()) && (CopilotToolCatalog.proposal(tool.name()) || "run_tests".equals(tool.name()) || "bash".equals(tool.name()))) {
                var value = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(tool.arguments());
                if (!(value instanceof ObjectNode arguments)) return new ToolOutcome.Completed(ToolOutcome.Code.INVALID_ARGUMENTS, "{\"code\":\"INVALID_ARGUMENTS\"}");
                String revision = tasks.planningRevision(task.id());
                if (revision == null || arguments.has("expected_revision") && (!arguments.path("expected_revision").isTextual() || !revision.equals(arguments.path("expected_revision").textValue()))) {
                    return new ToolOutcome.Completed(ToolOutcome.Code.CONFLICT, "{\"code\":\"REVISION_CONFLICT\",\"notice\":\"Read current files before replanning.\"}");
                }
                arguments.put("expected_revision", revision);
                tool = new CopilotToolEntity(tool.id(), tool.turnId(), tool.taskId(), tool.exerciseId(), tool.position(), tool.callId(), tool.name(),
                    json.writeValueAsString(arguments), tool.state(), tool.resultMessageId(), tool.createdAt(), tool.completedAt());
            }
            result = executePrepared(task, worker, tool);
        } catch (JsonProcessingException malformed) {
            return new ToolOutcome.Completed(ToolOutcome.Code.INVALID_ARGUMENTS, "{\"code\":\"INVALID_ARGUMENTS\"}");
        } catch (CopilotEditException mismatch) {
            return new ToolOutcome.Completed(ToolOutcome.Code.INVALID_ARGUMENTS, json.writeValueAsString(Map.of("code", mismatch.code(), "path", mismatch.path(), "lineCount", mismatch.lineCount(), "suggestedLine", mismatch.suggestedLine())));
        }
        return result.waiting() ? new ToolOutcome.Suspended() : new ToolOutcome.Completed(ToolOutcome.classify(json.readTree(result.body())), result.body());
    }
    public boolean explore(String name, String arguments) { return delegations.explore(name, arguments); }

    public ToolOutcome executeExplorations(CopilotTaskEntity task, String worker, List<CopilotToolEntity> tools) throws JsonProcessingException {
        if (!"MAIN".equals(task.lane()) || tools.isEmpty() || tools.size() > 3 || tools.stream().anyMatch(tool -> !explore(tool.name(), tool.arguments()))) {
            return new ToolOutcome.Completed(ToolOutcome.Code.DENIED, "{\"code\":\"DELEGATION_NOT_ALLOWED\"}");
        }
        var result = delegations.executeBatch(task, worker, tools);
        return result.waiting() ? new ToolOutcome.Suspended() : new ToolOutcome.Completed(ToolOutcome.classify(json.readTree(result.body())), result.body());
    }

    private CopilotToolExecutionDTO executePrepared(CopilotTaskEntity task, String worker, CopilotToolEntity tool) throws JsonProcessingException, InterruptedException {
        if (!CopilotToolPolicy.allows(task.lane(), tool.name())) return new CopilotToolExecutionDTO(false,
            json.writeValueAsString(Map.of("code", "BTW".equals(task.lane()) ? "BTW_READ_ONLY" : "DELEGATION_NOT_ALLOWED",
                "notice", "This task lane does not allow the requested tool; no child or workspace operation was created.")));
        if ("delegate_task".equals(tool.name())) return delegations.execute(task, worker, tool);
        if ("web_search".equals(tool.name()) || "web_read".equals(tool.name())) return new CopilotToolExecutionDTO(false, web.execute(task.sessionHash(), task.exerciseId(), tool.name(), tool.arguments()));
        if ("read_source".equals(tool.name())) return new CopilotToolExecutionDTO(false, references.execute(task.sessionHash(), task.exerciseId(), tool.arguments()));
        if ("load_skill".equals(tool.name())) return new CopilotToolExecutionDTO(false, skills.execute(task.id(), worker, tool.arguments()));
        if ("run_tests".equals(tool.name())) return tests.execute(task, worker, tool);
        if ("bash".equals(tool.name())) return commands.execute(task, worker, tool);
        if ("read_evidence".equals(tool.name())) return new CopilotToolExecutionDTO(false, evidence.execute(task.id(), worker, tool.arguments()));
        if (!CopilotToolCatalog.proposal(tool.name())) {
            return new CopilotToolExecutionDTO(false, json.writeValueAsString(reads.execute(task.sessionHash(), task.exerciseId(), tool.name(), tool.arguments())));
        }
        try {
            var prepared = preparation.prepare(task.sessionHash(), task.exerciseId(), tool.name(), tool.arguments());
            if (proposals.register(task.id(), worker, tool.id(), prepared)) return new CopilotToolExecutionDTO(true, null);
            return new CopilotToolExecutionDTO(false, json.writeValueAsString(Map.of("code", "PROPOSAL_PREVIOUSLY_REJECTED")));
        } catch (BusinessException rejected) {
            return new CopilotToolExecutionDTO(false, json.writeValueAsString(Map.of("code", rejected.errorCode().code())));
        }
    }
}
