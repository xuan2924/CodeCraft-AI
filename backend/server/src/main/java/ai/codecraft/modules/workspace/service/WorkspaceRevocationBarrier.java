package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.identity.service.IdentityRevocationBarrier;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class WorkspaceRevocationBarrier implements IdentityRevocationBarrier {
    private final WorkspaceFenceTransactionService store;
    private final SandboxWorkspaceGateway sandbox;
    private final ObjectMapper json;
    public WorkspaceRevocationBarrier(WorkspaceFenceTransactionService store, SandboxWorkspaceGateway sandbox, ObjectMapper json) {
        this.store = store;
        this.sandbox = sandbox;
        this.json = json;
    }
    @Override
    public List<String> pendingPreparations(String userId, String sessionHash) {
        return store.pending(userId, sessionHash).stream().map(ExerciseEntity::preparationOperationId).toList();
    }

    @Override
    public void fence(List<String> preparationIds) {
        for (String id : preparationIds) {
            ExerciseEntity exercise = store.preparation(id);
            if (exercise != null && exercise.state().equals("PREPARING")) fencePreparation(exercise);
        }
    }

    public void fencePreparation(ExerciseEntity exercise) {
        // Preparation has a single terminal fence, shared by revocation and expiration recovery.
        sandbox.closeGate(exercise.workspaceInstanceId(), exercise.preparationOperationId());
        WorkspaceSnapshot result = sandbox.query(exercise.workspaceInstanceId());
        if (result != null && result.getPrepared()) {
            ProblemVersionEntity problem = store.problem(exercise.problemVersionId());
            if (result.getCancelled() || result.getRevision() != 0 || problem == null
                || !result.getWorkspaceInstanceId().equals(exercise.workspaceInstanceId())
                || !result.getOperationId().equals(exercise.preparationOperationId())
                || !result.getProblem().getProblemId().equals(problem.problemId())
                || !result.getProblem().getVersion().equals(problem.version())
                || !result.getProblem().getPackageSha256().equals(problem.packageSha256())
                || !result.getProblem().getExecutionImageDigest().equals(problem.executionImageDigest())
                || !result.getProblem().getPlatform().equals(problem.platform())) {
                throw new IllegalStateException("Preparation receipt cannot be reconciled during revocation");
            }
            try { store.prepared(exercise, result.getRevision(), json.writeValueAsString(result.getPathsList())); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Invalid preparation paths", error); }
        } else {
            WorkspaceSnapshot cancelled = sandbox.cancel(exercise.workspaceInstanceId(), exercise.preparationOperationId());
            if (!cancelled.getCancelled() || !cancelled.getWorkspaceInstanceId().equals(exercise.workspaceInstanceId())) {
                throw new IllegalStateException("Cancellation receipt cannot be reconciled during revocation");
            }
            store.cancelled(exercise);
        }
    }
}
