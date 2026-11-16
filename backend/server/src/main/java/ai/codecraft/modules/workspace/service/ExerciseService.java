package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.entity.RevocationIntentEntity;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.ExerciseResponse;
import ai.codecraft.modules.workspace.model.dto.ExercisePauseResponse;
import java.time.Clock;
import ai.codecraft.modules.workspace.model.dto.FinalizationResponse;
import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceOperationResponse;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.workspace.v1.ReadWorkspaceFileResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

@Service
public class ExerciseService {
    private final ExerciseTransactionService store;
    private final SandboxWorkspaceGateway sandbox;
    private final ObjectMapper json;
    private final ProblemService problems;
    private final IdentityTransactionService identity;
    private final WorkspaceWriteTransactionService writes;
    private final FinalizationStore finals;
    private final FinalizationCoordinator finalization;
    private final StorageCapacityService capacity;
    private final ExercisePauseStore pauses;
    private final Clock clock;

    public ExerciseService(ExerciseTransactionService store, SandboxWorkspaceGateway sandbox, @Qualifier("workspaceJson") ObjectMapper json, ProblemService problems, IdentityTransactionService identity, WorkspaceWriteTransactionService writes, FinalizationStore finals, FinalizationCoordinator finalization, StorageCapacityService capacity, ExercisePauseStore pauses, Clock clock) {
        this.store = store;
        this.sandbox = sandbox;
        this.json = json;
        this.problems = problems;
        this.identity = identity;
        this.writes = writes;
        this.finals = finals;
        this.finalization = finalization; this.capacity = capacity;
        this.pauses = pauses; this.clock = clock;
    }

    public WorkspaceOperationResponse pause(String session, String id, String key, String credential, boolean resume) {
        return describe(pauses.request(session, id, key, credential, resume));
    }

    public WorkspaceOperationResponse create(String session, String version, String key) {
        return describe(store.create(session, version, key));
    }

    public WorkspaceOperationResponse operation(String session, String id) {
        RevocationIntentEntity revocation = identity.revocation(session, id);
        if (revocation != null) {
            String state = revocation.state().equals("FENCED") ? "PENDING" : revocation.state();
            return new WorkspaceOperationResponse(id, null, state, "/api/operations/" + id);
        }
        return writes.result(session, id);
    }
    public ExerciseResponse find(String session, String id) {
        store.find(session, id);
        finals.expire(id);
        return describe(store.find(session, id));
    }
    public ExerciseResponse current(String session) {
        ExerciseEntity exercise = store.current(session);
        finals.expire(exercise.id());
        return describe(store.find(session, exercise.id()));
    }

    public ExerciseResponse end(String session, String id, String key, String credential) {
        finals.end(session, id, key, credential);
        finalization.tryDrive(id);
        return describe(store.find(session, id));
    }

    public ExerciseResponse recoveryEnd(String session, String id, String key) {
        finals.recoveryEnd(session, id, key);
        finalization.tryDrive(id);
        return describe(store.find(session, id));
    }

    public ExerciseResponse start(String session, String id, String key, String credential) {
        ExerciseEntity exercise = store.find(session, id);
        int duration = problems.describe(exercise.problemVersionId()).durationSeconds();
        return describe(store.start(session, id, key, duration, credential));
    }

    public WorkspaceOperationResponse retry(String session, String id, String key) {
        var previous = store.existingRetry(session, id, key);
        return describe(previous != null ? previous : capacity.admit(session, id, measured -> store.retry(session, id, key, measured)));
    }

    public WorkspaceOperationResponse abandon(String session, String id, String key) {
        return describe(store.abandon(session, id, key));
    }

    public WorkspaceFileResponse read(String session, String exerciseId, String path) {
        ExerciseEntity exercise = store.find(session, exerciseId);
        if (exercise.state().equals("ENDED") && exercise.revision() == null) {
            throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        }
        if (exercise.revision() == null) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (path == null || path.length() > 4096 || !path.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")
            || List.of(path.split("/")).stream().anyMatch(part -> part.equals(".") || part.equals(".."))) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        }
        try {
            ReadWorkspaceFileResponse result = sandbox.read(exercise.workspaceInstanceId(), path, exercise.revision());
            // A slow RPC must not return private content under an identity revoked while it was in flight.
            ExerciseEntity current = store.find(session, exerciseId);
            if (!current.workspaceInstanceId().equals(exercise.workspaceInstanceId()) || current.revision() == null) {
                throw new BusinessException(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
            }
            if (!result.getWorkspaceInstanceId().equals(exercise.workspaceInstanceId())
                || !result.getPath().equals(path) || result.getRevision() != exercise.revision()) {
                throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
            }
            return new WorkspaceFileResponse(result.getWorkspaceInstanceId(), Long.toString(result.getRevision()), path, result.getContent());
        } catch (StatusRuntimeException error) {
            WorkspaceErrorCode code = switch (error.getStatus().getCode()) {
                case NOT_FOUND -> WorkspaceErrorCode.RESOURCE_NOT_FOUND;
                case INVALID_ARGUMENT -> WorkspaceErrorCode.INVALID_REQUEST;
                case ABORTED -> WorkspaceErrorCode.REVISION_CONFLICT;
                default -> WorkspaceErrorCode.WORKSPACE_UNAVAILABLE;
            };
            throw new BusinessException(code, error);
        }
    }

    private WorkspaceOperationResponse describe(WorkspaceOperationEntity operation) {
        return new WorkspaceOperationResponse(operation.id(), operation.exerciseId(), operation.state(), "/api/operations/" + operation.id());
    }

    private ExerciseResponse describe(ExerciseEntity exercise) {
        try {
            List<String> paths = json.readValue(exercise.paths(), new TypeReference<List<String>>() {});
            FinalizationEntity value = finals.find(exercise.id());
            FinalizationResponse result = value == null ? null : new FinalizationResponse(value.id(),
                value.finalRevision() == null ? null : value.finalRevision().toString(), value.verification(), value.resultTaskId(), value.attempts(),
                value.maxAttempts(), value.deadlineAt(), value.endedAt(), value.frozenEpoch() == null ? "RECONCILIATION_REQUIRED" : value.endedAt() == null ? "FINAL_TESTING" : "ENDED");
            var pause = pauses.find(exercise.id());
            var pauseResponse = pause == null ? null : new ExercisePauseResponse(pause.pauseOperationId(), pause.resumeOperationId(),
                pause.remainingMillis(), pause.confirmedAt());
            return new ExerciseResponse(exercise.id(), exercise.problemVersionId(), exercise.workspaceInstanceId(),
                exercise.preparationOperationId(), exercise.state(), exercise.revision() == null ? null : exercise.revision().toString(),
                Long.toString(exercise.stateVersion()), List.copyOf(paths), exercise.startedAt(), exercise.deadlineAt(), "ENDED".equals(exercise.state()) && value != null && value.finalRevision() != null && value.endedAt() != null ? "AVAILABLE" : "NOT_READY",
                exercise.preparationFailureReason(), exercise.endReason(), exercise.cleanupState(), result, pauseResponse, clock.instant());
        } catch (IOException error) {
            throw new IllegalStateException("Stored workspace paths are invalid", error);
        }
    }
}
