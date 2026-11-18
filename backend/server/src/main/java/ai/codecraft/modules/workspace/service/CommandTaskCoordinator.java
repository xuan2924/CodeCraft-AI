package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.workspace.v1.CommandSnapshot;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** A committed dispatch is queried or fenced, never replayed as another Bash process. */
@Service
public class CommandTaskCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(CommandTaskCoordinator.class);
    private final CommandTaskStore store;
    private final SandboxWorkspaceGateway sandbox;

    public CommandTaskCoordinator(CommandTaskStore store, SandboxWorkspaceGateway sandbox) {
        this.store = store; this.sandbox = sandbox;
    }

    @Scheduled(fixedDelay = 1000)
    public void recover() {
        for (var task : store.pending()) {
            try { observe(task); }
            catch (RuntimeException error) { LOG.warn("Command {} remains unresolved ({})", task.id(), error.getClass().getSimpleName()); }
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void prune() {
        for (var task : store.evictionCandidates()) prune(task);
    }

    /** Bounded pressure relief after a definitively rejected new admission. */
    public boolean pruneForAdmission(String exercise) {
        boolean confirmed = false;
        for (var task : store.pressureCandidates(exercise)) confirmed = prune(task) || confirmed;
        return confirmed;
    }

    private boolean prune(CommandTaskEntity task) {
        try {
            if (!store.beginPruning(task.id())) return false;
            var receipt = sandbox.pruneCommand(task);
            if (!receipt.getComplete() || !receipt.getWorkspaceInstanceId().equals(task.workspaceInstanceId())
                || !receipt.getTaskId().equals(task.id()) || !receipt.getExecutionInstanceId().equals(task.executionInstanceId())) {
                throw new IllegalStateException("Invalid command pruning receipt");
            }
            store.pruned(task.id());
            return true;
        } catch (RuntimeException error) {
            LOG.warn("Command {} pruning remains unresolved ({})", task.id(), error.getClass().getSimpleName());
            return false;
        }
    }

    public void observe(CommandTaskEntity original) {
        if (original.stopRequested()) { cancel(original); return; }
        if (!original.dispatched() && !original.quiescent()) sandbox.ensureExecutionReady(original.workspaceInstanceId());
        String epoch = original.dispatched() ? original.hostEpoch() : sandbox.inventoryFor(original.workspaceInstanceId(), "", "").getHostEpoch();
        boolean claimed;
        try { claimed = store.claim(original.id(), epoch); }
        catch (IdentityFailure unavailable) {
            // The failed admission transaction is already rolled back. Safety
            // cancellation commits separately and never grants new execution.
            store.requestSystemStop(original.id());
            cancel(store.find(original.id()));
            return;
        }
        var task = store.find(original.id());
        if (task.pruned()) return;
        if (task.stopRequested()) { cancel(task); return; }
        if (claimed) { confirm(task, store.terminal(task.id()) ? sandbox.startTerminal(task) : sandbox.startCommand(task)); return; }
        if (!task.dispatched()) return;
        var snapshot = sandbox.command(task);
        if (snapshot == null) {
            // An absent registration is not permission to retry Start. An exact
            // cancellation tombstone also fences requests still in transit.
            cancel(task);
            return;
        }
        confirm(task, snapshot);
        sandbox.reconcileCommand(store.find(task.id()));
    }

    private void cancel(CommandTaskEntity task) {
        var receipt = sandbox.stopCommand(task);
        if (!receipt.getHostEpoch().matches("[A-Z2-7]{26}") || (receipt.getCancelledBeforeRegistration() && receipt.hasSnapshot())) {
            throw new IllegalStateException("Invalid command cancellation receipt");
        }
        if (receipt.getCancelledBeforeRegistration()) {
            store.cancelledBeforeRegistration(task.id());
            return;
        }
        if (!receipt.hasSnapshot()) throw new IllegalStateException("Missing command cancellation evidence");
        var snapshot = sandbox.command(task);
        if (snapshot == null) throw new IllegalStateException("Cancelled command registration disappeared");
        confirm(task, snapshot);
        sandbox.reconcileCommand(store.find(task.id()));
    }

    private void confirm(CommandTaskEntity task, CommandSnapshot snapshot) {
        WorkspaceSnapshot workspace = null;
        if (snapshot.hasMutation() && snapshot.getMutation().getChanged()) workspace = sandbox.query(task.workspaceInstanceId());
        store.observe(task.id(), snapshot, workspace);
    }
}
