package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.TaskStreamBatchDTO;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.TaskEventEntity;
import ai.codecraft.modules.workspace.model.entity.TaskStreamEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.TaskStreamRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskStreamStore {
    private final IdentityTransactionService identity;
    private final ExerciseRepository exercises;
    private final TestTaskRepository tasks;
    private final TaskStreamRepository streams;
    private final WorkspaceMaintenanceStore maintenance;
    public TaskStreamStore(IdentityTransactionService identity, ExerciseRepository exercises, TestTaskRepository tasks, TaskStreamRepository streams,
        WorkspaceMaintenanceStore maintenance) {
        this.identity = identity; this.exercises = exercises; this.tasks = tasks; this.streams = streams; this.maintenance = maintenance;
    }
    /** Authorization, resource ownership and event dequeue share the revocation user lock. */
    @Transactional(timeout = 3)
    public TaskStreamBatchDTO batch(String session, String exerciseId, String taskId, String cursor, boolean readEvents) {
        String user = identity.authorizePrivateDelivery(session).userId();
        ExerciseEntity exercise = exercises.find(exerciseId);
        if (exercise == null || !exercise.userId().equals(user)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(exerciseId)) throw new BusinessException(WorkspaceErrorCode.EXERCISE_DELETED);
        maintenance.pruningPlan(exerciseId);
        TestTaskEntity task = tasks.lock(taskId);
        if (task == null || !task.exerciseId().equals(exerciseId)) throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (tasks.retired(taskId)) throw new BusinessException(WorkspaceErrorCode.TEST_HISTORY_PRUNED);
        TaskStreamEntity stream = streams.find(taskId);
        long sequence = TaskStreamCursor.decode(stream, cursor);
        if (sequence < stream.firstSequence() - 1) throw new BusinessException(WorkspaceErrorCode.CURSOR_EXPIRED);
        StringBuilder text = new StringBuilder();
        if (readEvents) {
            for (TaskEventEntity event : streams.batch(taskId, sequence)) {
                text.append("id:").append(TaskStreamCursor.encode(stream, event.sequence())).append("\nevent:")
                    .append(event.type()).append("\ndata:").append(event.payload()).append("\n\n");
                sequence = event.sequence();
            }
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) throw new IllegalStateException("Task delivery batch exceeded limit");
        return new TaskStreamBatchDTO(user, TaskStreamCursor.encode(stream, sequence), bytes);
    }
}
