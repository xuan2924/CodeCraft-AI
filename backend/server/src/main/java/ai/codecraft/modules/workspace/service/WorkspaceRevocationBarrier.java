package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.identity.service.IdentityRevocationBarrier;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class WorkspaceRevocationBarrier implements IdentityRevocationBarrier {
    private final WorkspaceFenceTransactionService store;
    private final SandboxWorkspaceGateway sandbox;
    private final ObjectMapper json;
    private final PreparationReceiptValidator receipts;
    private final WorkspaceCoordinationStore coordination;
    private final WorkspaceWriteCoordinator writes;
    public WorkspaceRevocationBarrier(WorkspaceFenceTransactionService store, SandboxWorkspaceGateway sandbox,
                                      ObjectMapper json, PreparationReceiptValidator receipts, WorkspaceCoordinationStore coordination, WorkspaceWriteCoordinator writes) {
        this.store = store;
        this.sandbox = sandbox;
        this.json = json;
        this.receipts = receipts;
        this.coordination = coordination;
        this.writes = writes;
    }
    @Override
    public List<String> pendingPreparations(String userId, String sessionHash) {
        List<String> captured = new ArrayList<>(store.pending(userId, sessionHash).stream().map(ExerciseEntity::preparationOperationId).toList());
        captured.addAll(coordination.captureFences(userId));
        return List.copyOf(captured);
    }

    @Override
    public void fence(List<String> preparationIds) {
        for (String id : preparationIds) {
            ExerciseEntity exercise = store.preparation(id);
            if (exercise != null && exercise.state().equals("PREPARING")) fencePreparation(exercise);
            else if (coordination.transition(id) != null) writes.drive(id);
        }
    }

    public void fencePreparation(ExerciseEntity exercise) {
        // Preparation has a single terminal fence, shared by revocation and expiration recovery.
        sandbox.closeGate(exercise.workspaceInstanceId(), exercise.preparationOperationId());
        WorkspaceSnapshot result = sandbox.query(exercise.workspaceInstanceId());
        if (result != null && result.getPrepared()) {
            ProblemVersionEntity problem = store.problem(exercise.problemVersionId());
            if (!receipts.matches(result, exercise.workspaceInstanceId(), exercise.preparationOperationId(), problem)) {
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
