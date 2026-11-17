package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.repository.CopilotDelegationRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Uses only repositories: task/execution stores may depend on this without a service cycle. */
@Component
public class CopilotDelegationLifecycle {
    private final CopilotDelegationRepository delegations;
    private final CopilotTaskRepository tasks;
    private final Clock clock;

    public CopilotDelegationLifecycle(CopilotDelegationRepository delegations, CopilotTaskRepository tasks, Clock clock) {
        this.delegations = delegations; this.tasks = tasks; this.clock = clock;
    }

    public boolean waiting(String parent) { return delegations.pending(parent) != null; }

    /** Parent ownership is already locked; normal child recovery converges its proposals/tests/commands. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stop(String parent) {
        for (var pending : delegations.pendingAll(parent)) tasks.stop(pending.childTaskId(), clock.instant());
    }
}
