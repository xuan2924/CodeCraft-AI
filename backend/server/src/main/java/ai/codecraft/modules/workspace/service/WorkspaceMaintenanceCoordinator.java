package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.TestHistoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceMaintenanceCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceMaintenanceCoordinator.class);
    private final WorkspaceMaintenanceStore store;
    private final SandboxWorkspaceGateway sandbox;

    public WorkspaceMaintenanceCoordinator(WorkspaceMaintenanceStore store, SandboxWorkspaceGateway sandbox) {
        this.store = store; this.sandbox = sandbox;
    }

    @Scheduled(fixedDelayString = "${workspace.retention.collection-delay-millis:5000}")
    public void collect() { for (String id : store.candidates()) collect(id); }

    /** Also used before rejecting capacity admission. No RPC runs in a DB transaction. */
    public void collect(String id) {
        try {
            for (var record : store.pruningPlan(id)) prune(id, record);
            var plan = store.plan(id);
            if (plan == null) return;
            var receipt = sandbox.collectWorkspace(plan.workspaceInstanceId(), plan.revision(), plan.retainedRevisions());
            if (!receipt.getWorkspaceInstanceId().equals(plan.workspaceInstanceId()) || receipt.getRevision() != plan.revision()) {
                throw new IllegalStateException("Invalid collection receipt");
            }
        } catch (RuntimeException failure) {
            // Revision races are retried with a fresh reference snapshot. Never
            // authorize stale collection or weaken pins after a storage failure.
            LOG.debug("Workspace collection deferred for {} ({})", id, failure.getClass().getSimpleName());
        } finally {
            store.attempted(id);
        }
    }

    private void prune(String id, TestHistoryRecord record) {
        try {
            store.pruningAttempted(record.taskId());
            var receipt = sandbox.pruneTestEvidence(id, record);
            if (!receipt.getComplete() || !receipt.getWorkspaceInstanceId().equals(record.workspaceInstanceId())
                || !receipt.getTaskId().equals(record.taskId()) || !receipt.getExecutionInstanceId().equals(record.executionInstanceId())) {
                throw new IllegalStateException("Incomplete test evidence pruning");
            }
            store.pruned(id, record.taskId());
        } catch (RuntimeException failure) {
            LOG.debug("Test history cleanup deferred for {} ({})", record.taskId(), failure.getClass().getSimpleName());
        }
    }
}
