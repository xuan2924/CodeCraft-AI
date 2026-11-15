package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.repository.WorkspaceHistoryRepository;
import org.springframework.stereotype.Service;

/** History metadata shares exercise accounting/deletion; executions remain FILE_BATCH operations. */
@Service
public class WorkspaceHistoryData implements ExerciseDataContributor {
    private final WorkspaceHistoryRepository history;
    public WorkspaceHistoryData(WorkspaceHistoryRepository history) { this.history = history; }
    @Override public long retainedBytes(String exerciseId) { return history.retainedBytes(exerciseId); }
    @Override public boolean quiescent(String exerciseId) { return true; }
    @Override public void erase(String exerciseId) {
        history.eraseRestores(exerciseId);
        history.eraseChanges(exerciseId);
    }
}
