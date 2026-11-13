package ai.codecraft.modules.workspace.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class StorageCapacityCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(StorageCapacityCoordinator.class);
    private final StorageCapacityStore store;
    private final StorageCapacityService capacity;

    public StorageCapacityCoordinator(StorageCapacityStore store, StorageCapacityService capacity) { this.store = store; this.capacity = capacity; }

    @Scheduled(fixedDelayString = "${workspace.storage.reconciliation-delay-millis:2000}")
    public void reconcile() {
        for (String exercise : store.pendingExercises()) {
            try { capacity.reconcile(exercise); }
            catch (RuntimeException failure) { LOG.debug("Storage credit remains reserved for {} ({})", exercise, failure.getClass().getSimpleName()); }
            finally { store.attempted(exercise); }
        }
    }
}
