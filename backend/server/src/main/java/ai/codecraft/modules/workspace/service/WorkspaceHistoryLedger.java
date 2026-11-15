package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceChangeEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.repository.WorkspaceHistoryRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records references before the same transaction exposes a newly confirmed revision to collection. */
@Service
public class WorkspaceHistoryLedger {
    private final WorkspaceHistoryRepository history;
    private final ExerciseDataService data;
    private final Clock clock;

    public WorkspaceHistoryLedger(WorkspaceHistoryRepository history, ExerciseDataService data, Clock clock) {
        this.history = history; this.data = data; this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void mutation(WorkspaceOperationEntity operation, long revision) {
        record(operation.id(), operation.exerciseId(), operation.workspaceInstanceId(), revision - 1, revision, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void command(CommandTaskEntity task, long revision) {
        record(task.id(), task.exerciseId(), task.workspaceInstanceId(), task.revision(), revision, true);
    }

    private void record(String id, String exercise, String workspace, long base, long result, boolean command) {
        if (base < 0 || result <= base || result != base + 1) throw new IllegalStateException("Invalid code history revision");
        var previous = history.find(id);
        if (previous != null) {
            if (!previous.exerciseId().equals(exercise) || !previous.workspaceInstanceId().equals(workspace)
                || previous.baseRevision() != base || previous.resultRevision() != result) throw new IllegalStateException("Code history identity changed");
            return;
        }
        var restore = command ? null : history.restore(id);
        var context = restore == null ? data.changeContext(exercise, id) : null;
        String source = command ? "BASH" : restore != null ? restore.mode() : context != null ? "AI_PATCH" : "MANUAL";
        history.insert(new WorkspaceChangeEntity(id, exercise, workspace, source, base, result,
            context == null ? null : context.taskId(), context == null ? null : context.conversationId(),
            restore == null ? null : restore.changeId(), true, clock.instant()));
        history.retireExcess(exercise);
    }
}
