package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.StartTestRequest;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.dto.TestHistoryResponse;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import org.springframework.stereotype.Service;

@Service
public class TestTaskService {
    private final TestTaskStore store;
    private final WorkspaceWriteTransactionService writes;
    private final WorkspaceWriteCoordinator fences;
    private final TestTaskCoordinator coordinator;
    private final StorageCapacityService capacity;

    public TestTaskService(TestTaskStore store, WorkspaceWriteTransactionService writes, WorkspaceWriteCoordinator fences, TestTaskCoordinator coordinator, StorageCapacityService capacity) {
        this.store = store; this.writes = writes; this.fences = fences; this.coordinator = coordinator; this.capacity = capacity;
    }

    public TestTaskResponse start(String session, String exercise, String key, String credential, StartTestRequest input) {
        TestTaskEntity task = store.previous(session, exercise, key, credential, input);
        if (task == null) {
            String transition = writes.ensureOpen(session, exercise, credential);
            if (transition != null) fences.tryDrive(transition);
            task = capacity.admitHeavy(session, exercise, measured -> store.accept(session, exercise, key, credential, input, measured));
        }
        // Dispatch belongs to the durable worker, never to this request lifetime.
        return store.query(session, exercise, task.id());
    }

    public TestTaskResponse stop(String session, String exercise, String task, String key, String credential) {
        coordinator.tryStop(store.acceptStop(session, exercise, task, key, credential));
        return store.query(session, exercise, task);
    }

    public TestTaskResponse recoveryStop(String session, String exercise, String task, String key) {
        coordinator.tryStop(store.acceptRecoveryStop(session, exercise, task, key));
        return store.query(session, exercise, task);
    }

    public TestTaskResponse query(String session, String exercise, String task) { return store.query(session, exercise, task); }
    public TestHistoryResponse recent(String session, String exercise) { return store.recent(session, exercise); }
}
