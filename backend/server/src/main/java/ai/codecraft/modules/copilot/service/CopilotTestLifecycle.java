package ai.codecraft.modules.copilot.service;

import ai.codecraft.modules.copilot.repository.CopilotTestRepository;
import ai.codecraft.modules.workspace.service.TestTaskStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Parent convergence uses only its persisted child binding, never the exercise's independent manual test. */
@Service
public class CopilotTestLifecycle {
    private final CopilotTestRepository tests;
    private final TestTaskStore executions;
    public CopilotTestLifecycle(CopilotTestRepository tests, TestTaskStore executions) { this.tests = tests; this.executions = executions; }
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean waiting(String task) { return tests.waiting(task); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void stop(String task) {
        var child = tests.pending(task);
        if (child != null) executions.requestSystemStop(child.exerciseId(), child.testTaskId(), "copilot-stop:" + task);
    }
}
