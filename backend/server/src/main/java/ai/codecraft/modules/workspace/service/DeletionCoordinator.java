package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.DeletionEntity;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Only this worker runs cleanup. HTTP retry merely advances its durable schedule. */
@Service
public class DeletionCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(DeletionCoordinator.class);
    private final DeletionStore store;
    private final SandboxWorkspaceGateway sandbox;

    public DeletionCoordinator(DeletionStore store, SandboxWorkspaceGateway sandbox) {
        this.store = store; this.sandbox = sandbox;
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void recover() {
        for (String id : store.expired()) store.expire(id);
        for (DeletionEntity value : store.due()) drive(value);
        store.expireReceipts();
    }

    public synchronized void drive(DeletionEntity value) {
        String token = UUID.randomUUID().toString();
        if (!store.claim(value.exerciseId(), token)) return;
        try {
            if (!store.reconciled(value.exerciseId())) {
                store.failed(value, token, "RECONCILIATION_REQUIRED");
                return;
            }
            var instances = store.workspaceInstances(value.exerciseId());
            if (!instances.contains(value.workspaceInstanceId())) throw new IllegalStateException("Missing workspace ownership");
            for (String instance : instances) {
                var receipt = sandbox.deleteWorkspace(instance);
                if (!receipt.getWorkspaceInstanceId().equals(instance) || !receipt.getComplete()) {
                    throw new IllegalStateException("Incomplete deletion receipt");
                }
            }
            store.complete(value.exerciseId(), token);
        } catch (RuntimeException error) {
            store.failed(value, token, "STORAGE_CLEANUP_FAILED");
            LOG.warn("Exercise {} cleanup remains pending ({})", value.exerciseId(), error.getClass().getSimpleName());
        }
    }
}
