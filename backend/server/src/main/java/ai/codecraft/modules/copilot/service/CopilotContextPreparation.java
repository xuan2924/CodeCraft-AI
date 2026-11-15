package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.config.CopilotContextProperties;
import ai.codecraft.modules.copilot.config.CopilotProperties;
import ai.codecraft.modules.copilot.model.dto.CopilotContextInputDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotContextPreparedDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotContextTicketDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotSteerEntity;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.copilot.repository.CopilotSteerRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CopilotContextPreparation {
    private final CopilotCompactionRepository contexts;
    private final CopilotSteerRepository steers;
    private final CopilotContextPlanner planner;
    private final CopilotCallEvidence evidence;
    private final CopilotContextProperties contextLimits;
    private final CopilotProperties executionLimits;
    private final CopilotSkillContext skills;

    public CopilotContextPreparation(CopilotCompactionRepository contexts, CopilotSteerRepository steers,
        CopilotContextPlanner planner, CopilotCallEvidence evidence, CopilotContextProperties contextLimits, CopilotProperties executionLimits, CopilotSkillContext skills) {
        this.contexts = contexts; this.steers = steers; this.planner = planner; this.evidence = evidence;
        this.contextLimits = contextLimits; this.executionLimits = executionLimits; this.skills = skills;
    }

    @Transactional(propagation = Propagation.NEVER)
    public CopilotContextPreparedDTO prepare(CopilotContextTicketDTO ticket) throws InterruptedException {
        if (ticket == null) return null;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Context preparation cancelled");
        String summary = null;
        if (ticket.summaryId() != null) {
            var retained = contexts.find(ticket.summaryId());
            if (retained == null || !ticket.exerciseId().equals(retained.exerciseId()) || !"APPLIED".equals(retained.state())) {
                throw new IllegalStateException("Context summary reference is not retained by this exercise");
            }
            summary = retained.summaryPayload();
        }
        // Raw evidence can be much larger than the prepared input. Admit a whole legal batch plus a bounded prefix;
        // the planner never discards an unfinished trailing unit just to satisfy the input limit.
        long rawBudget = Math.max(executionLimits.contextBytes(),
            2L * (executionLimits.responseBytes() + (long) executionLimits.batchTools() * CopilotProperties.MAX_TOOL_PAYLOAD_BYTES));
        var messages = contexts.source(ticket.exerciseId(), ticket.throughSequence(), ticket.highWater(), contextLimits.sourceMessages(), rawBudget);
        if (messages.isEmpty() && ticket.highWater() > ticket.throughSequence()) throw new IllegalStateException("Context evidence is unavailable within its source bound");
        List<CopilotMessageEntity> goals = ticket.taskId() == null ? List.of() : contexts.goals(ticket.taskId());
        List<CopilotSteerEntity> adjustments = ticket.steerIds().isEmpty() ? List.of() : steers.pending(ticket.taskId());
        if (!adjustments.stream().map(CopilotSteerEntity::id).toList().equals(ticket.steerIds())) return null;
        boolean more = !messages.isEmpty() && messages.getLast().sequence() < ticket.highWater();
        var input = new CopilotContextInputDTO(ticket.summaryId(), summary, ticket.throughSequence(), messages, goals,
            adjustments.stream().map(CopilotSteerEntity::messagePayload).toList(), more, ticket.textHistoryThroughSequence(), skills.prepare(ticket));
        var plan = planner.plan(ticket.target(), input);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Context preparation cancelled");
        return new CopilotContextPreparedDTO(ticket, input, plan, adjustments, evidence.configuration(ticket.target()));
    }
}
