package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.StartTestRequest;
import ai.codecraft.modules.workspace.model.dto.TestTaskResponse;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TestTaskService {
    private final TestTaskStore store;
    private final WorkspaceWriteTransactionService writes;
    private final WorkspaceWriteCoordinator fences;

    public TestTaskService(TestTaskStore store, WorkspaceWriteTransactionService writes, WorkspaceWriteCoordinator fences) {
        this.store = store; this.writes = writes; this.fences = fences;
    }

    public TestTaskResponse start(String session, String exercise, String key, String credential, StartTestRequest input) {
        TestTaskEntity task = store.previous(session, exercise, key, credential, input);
        if (task == null) {
            String transition = writes.ensureOpen(session, exercise, credential);
            if (transition != null) fences.tryDrive(transition);
            task = store.accept(session, exercise, key, credential, input);
        }
        // Dispatch belongs to the durable worker, never to this request lifetime.
        return store.query(session, exercise, task.id());
    }

    public TestTaskResponse query(String session, String exercise, String task) { return store.query(session, exercise, task); }
    public List<TestTaskResponse> recent(String session, String exercise) { return store.recent(session, exercise); }
}
