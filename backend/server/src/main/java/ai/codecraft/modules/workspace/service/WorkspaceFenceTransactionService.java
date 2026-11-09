package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceFenceTransactionService {
    private final ExerciseRepository exercises;
    private final ProblemRepository problems;
    public WorkspaceFenceTransactionService(ExerciseRepository exercises, ProblemRepository problems) {
        this.exercises = exercises;
        this.problems = problems;
    }
    @Transactional(readOnly = true)
    public List<ExerciseEntity> pending(String user, String session) { return exercises.pendingForRevocation(user, session); }
    @Transactional(readOnly = true)
    public ProblemVersionEntity problem(String id) { return problems.find(id); }
    @Transactional(readOnly = true)
    public ExerciseEntity preparation(String id) { return exercises.findPreparation(id); }
    @Transactional
    public void prepared(ExerciseEntity exercise, long revision, String paths) {
        if (exercises.prepared(exercise.id(), exercise.workspaceInstanceId(), exercise.preparationOperationId(), revision, paths) == 1) {
            exercises.operationState(exercise.preparationOperationId(), "SUCCEEDED");
        }
    }
    @Transactional
    public void cancelled(ExerciseEntity exercise) {
        exercises.failureIntent(exercise.id(), exercise.preparationOperationId(), "AUTHORIZATION_REVOKED");
        if (exercises.preparationFailed(exercise.id(), exercise.preparationOperationId()) == 1) {
            exercises.operationState(exercise.preparationOperationId(), "REJECTED");
        }
    }
}
