package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.PreparationAuthorizationDTO;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.ProblemVersionEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkspacePreparationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspacePreparationWorker.class);
    private final ExerciseTransactionService exercises;
    private final ProblemTransactionService problems;
    private final SandboxWorkspaceGateway sandbox;
    private final ObjectMapper json;
    private final WorkspaceRevocationBarrier revocations;
    private final PreparationReceiptValidator receipts;
    private final WorkspaceWriteCoordinator writes;

    public WorkspacePreparationWorker(ExerciseTransactionService exercises, ProblemTransactionService problems,
                                      SandboxWorkspaceGateway sandbox, ObjectMapper json, WorkspaceRevocationBarrier revocations,
                                      PreparationReceiptValidator receipts, WorkspaceWriteCoordinator writes) {
        this.exercises = exercises;
        this.problems = problems;
        this.sandbox = sandbox;
        this.json = json;
        this.revocations = revocations;
        this.receipts = receipts;
        this.writes = writes;
    }

    @Scheduled(fixedDelay = 1000)
    public void reconcile() {
        for (WorkspaceOperationEntity operation : exercises.pending()) {
            try {
                // Persist rotation before RPC or any early exit; broken old instances cannot monopolize the batch.
                exercises.attempted(operation.id());
                if (operation.kind().equals("ABANDON")) {
                    writes.drive(operation.id());
                    WorkspaceSnapshot cancelled = sandbox.cancel(operation.workspaceInstanceId(), operation.id());
                    if (!cancelled.getCancelled() || !cancelled.getWorkspaceInstanceId().equals(operation.workspaceInstanceId())) {
                        throw new IllegalStateException("Cancellation receipt mismatch");
                    }
                    exercises.cancelled(operation);
                    continue;
                }
                ExerciseEntity exercise = exercises.preparation(operation.exerciseId());
                if (exercise == null || !exercise.state().equals("PREPARING")
                    || !exercise.preparationOperationId().equals(operation.id())) continue;
                PreparationAuthorizationDTO authorization = exercises.preparationAuthorization(operation);
                if (authorization == null) {
                    revocations.fencePreparation(exercise);
                    continue;
                }
                ProblemVersionEntity problem = problems.find(exercise.problemVersionId());
                WorkspaceSnapshot result;
                try {
                    result = exercise.preparationFailureReason() == null ? sandbox.prepare(operation, problem, authorization.notAfter())
                        : sandbox.cancel(operation.workspaceInstanceId(), operation.id());
                } catch (StatusRuntimeException error) {
                    switch (error.getStatus().getCode()) {
                        case INVALID_ARGUMENT, FAILED_PRECONDITION -> {
                            // Persist the reason before attempting cleanup, so a lost response cannot erase it.
                            boolean authorizationExpired = exercises.preparationAuthorization(operation) == null;
                            exercises.failureIntent(operation, authorizationExpired ? "AUTHORIZATION_REVOKED"
                                : error.getStatus().getCode().name().equals("INVALID_ARGUMENT")
                                    ? "INVALID_PREPARATION_REQUEST" : "PREPARATION_PRECONDITION_FAILED");
                            result = sandbox.cancel(operation.workspaceInstanceId(), operation.id());
                        }
                        default -> throw error;
                    }
                }
                if (result.getCancelled() && result.getWorkspaceInstanceId().equals(operation.workspaceInstanceId())
                    && result.getOperationId().equals(operation.id())) {
                    // Querying a tombstone alone does not prove physical cleanup finished.
                    sandbox.cancel(operation.workspaceInstanceId(), operation.id());
                    exercises.preparationFailed(operation);
                    continue;
                }
                if (!receipts.matches(result, operation.workspaceInstanceId(), operation.id(), problem)) {
                    throw new IllegalStateException("Preparation receipt identity mismatch");
                }
                exercises.prepared(operation, result.getRevision(), json.writeValueAsString(result.getPathsList()));
            } catch (Exception error) {
                exercises.unknown(operation.id());
                LOG.warn("Workspace preparation requires reconciliation ({})", error.getClass().getSimpleName());
            }
        }
    }
}
