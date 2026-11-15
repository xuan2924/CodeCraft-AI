package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.model.dto.CopilotContextPreparedDTO;
import ai.codecraft.modules.copilot.repository.CopilotCompactionRepository;
import ai.codecraft.modules.workspace.service.StorageCapacityService;
import org.springframework.stereotype.Service;

/** Worker-side preparation entry. Observation endpoints must never invoke this path. */
@Service
public class CopilotContextService {
    private final CopilotExecutionStore execution;
    private final CopilotContextPreparation preparation;
    private final CopilotCompactionRepository contexts;
    private final CopilotCompactionStore admissions;
    private final CopilotCompactionRunner summaries;
    private final StorageCapacityService capacity;

    public CopilotContextService(CopilotExecutionStore execution, CopilotContextPreparation preparation,
        CopilotCompactionRepository contexts, CopilotCompactionStore admissions, CopilotCompactionRunner summaries, StorageCapacityService capacity) {
        this.execution = execution; this.preparation = preparation; this.contexts = contexts;
        this.admissions = admissions; this.summaries = summaries; this.capacity = capacity;
    }

    public CopilotContextPreparedDTO beforeModel(String task, String worker) throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            var ticket = execution.contextTicket(task, worker);
            if (ticket == null) return null;
            var prepared = preparation.prepare(ticket);
            if (prepared == null) return null;
            var pending = contexts.pending(ticket.exerciseId());
            String id = pending == null ? null : pending.id();
            if (id == null && prepared.plan().automaticNeeded() && prepared.plan().compaction() != null) {
                id = capacity.admit(ticket.sessionHash(), ticket.exerciseId(), measurement -> admissions.automatic(task, worker, prepared, measurement));
            }
            if (id == null) return prepared;
            summaries.run(id, worker, task);
            var outcome = contexts.find(id);
            if (outcome != null && "INTERRUPTED".equals(outcome.state()) && "MODEL_RESULT_UNKNOWN".equals(outcome.failureCode())) {
                execution.finish(task, worker, "INTERRUPTED", "MODEL_RESULT_UNKNOWN"); return null;
            }
            if (outcome != null && ("WAITING".equals(outcome.state()) || "RUNNING".equals(outcome.state()))) {
                execution.finish(task, worker, "PAUSED", "COPILOT_CONTEXT_REQUIRED"); return null;
            }
            // A bounded source page may not cover all old history. Continue only after a confirmed, materially smaller publication.
            if (outcome != null && "APPLIED".equals(outcome.state())) continue;
            var fresh = execution.contextTicket(task, worker);
            return fresh == null ? null : preparation.prepare(fresh);
        }
        throw new InterruptedException("Context preparation interrupted");
    }
}
