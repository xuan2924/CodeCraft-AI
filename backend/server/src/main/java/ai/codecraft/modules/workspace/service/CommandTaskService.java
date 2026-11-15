package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.CommandChangesResponse;
import ai.codecraft.modules.workspace.model.dto.WorkspaceFileDifferenceResponse;
import ai.codecraft.modules.workspace.model.dto.CommandHistoryResponse;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.workspace.v1.CommandChanges;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CommandTaskService {
    private final CommandTaskStore store;
    private final StorageCapacityService capacity;
    private final WorkspaceWriteTransactionService writes;
    private final WorkspaceWriteCoordinator fences;
    private final SandboxWorkspaceGateway sandbox;
    private final CommandTaskCoordinator coordinator;

    public CommandTaskService(CommandTaskStore store, StorageCapacityService capacity, WorkspaceWriteTransactionService writes,
        WorkspaceWriteCoordinator fences, SandboxWorkspaceGateway sandbox, CommandTaskCoordinator coordinator) {
        this.store = store; this.capacity = capacity; this.writes = writes; this.fences = fences; this.sandbox = sandbox; this.coordinator = coordinator;
    }

    public CommandTaskResponse start(String session, String exercise, String key, String credential, CommandStartRequest input) {
        var task = store.previous(session, exercise, key, input);
        if (task == null) {
            String transition = writes.ensureOpen(session, exercise, credential);
            if (transition != null) fences.tryDrive(transition);
            try { task = capacity.admit(session, exercise, measurement -> store.accept(session, exercise, key, credential, input, measurement)); }
            catch (BusinessException rejection) {
                if (rejection.errorCode() != WorkspaceErrorCode.STORAGE_BUDGET_EXCEEDED || !coordinator.pruneForAdmission(exercise)) throw rejection;
                task = capacity.admit(session, exercise, measurement -> store.accept(session, exercise, key, credential, input, measurement));
            }
        }
        return query(session, exercise, task.id());
    }

    public CommandTaskResponse stop(String session, String exercise, String id, String credential) {
        store.stop(session, exercise, id, credential);
        return query(session, exercise, id);
    }

    public CommandTaskResponse query(String session, String exercise, String id) { return store.response(store.read(session, exercise, id)); }

    public CommandHistoryResponse history(String session, String exercise, String after) { return store.recent(session, exercise, after); }

    public CommandChangesResponse changes(String session, String exercise, String id) {
        var task = store.read(session, exercise, id);
        if (!task.quiescent() || task.synchronization().equals("PENDING")) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        CommandChanges result;
        try { result = sandbox.commandChanges(task); }
        catch (StatusRuntimeException error) {
            store.read(session, exercise, id);
            var code = error.getStatus().getCode();
            if (Set.of(Status.Code.FAILED_PRECONDITION, Status.Code.INVALID_ARGUMENT, Status.Code.RESOURCE_EXHAUSTED,
                Status.Code.DATA_LOSS, Status.Code.NOT_FOUND).contains(code)) throw new BusinessException(WorkspaceErrorCode.COMMAND_OUTPUT_UNAVAILABLE, error);
            throw new BusinessException(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE, error);
        }
        store.read(session, exercise, id); // Private delivery is reauthorized after external I/O.
        if (!result.getWorkspaceInstanceId().equals(task.workspaceInstanceId()) || !result.getExecutionInstanceId().equals(task.executionInstanceId())
            || result.getBaseRevision() != task.revision() || result.getChangesCount() > 400) throw new IllegalStateException("Invalid command changes identity");
        long bytes = 0;
        var paths = new java.util.HashSet<String>();
        for (var change : result.getChangesList()) {
            var file = change.getChange();
            String path = file.getPath();
            if (!change.hasChange() || !Set.of("CREATE", "UPDATE", "DELETE").contains(file.getKind()) || path.length() > 4096
                || path.indexOf('\0') >= 0 || path.contains("\\") || !paths.add(path)
                || Arrays.stream(path.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
                throw new IllegalStateException("Invalid command file change");
            }
            bytes += file.getContent().getBytes(StandardCharsets.UTF_8).length + (long) change.getPreviousContent().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 20L * 1024 * 1024) throw new IllegalStateException("Command changes exceed managed tree bounds");
        }
        return new CommandChangesResponse(id, exercise, task.workspaceInstanceId(), task.executionInstanceId(), Long.toString(task.revision()),
            result.getChangesList().stream().map(change -> new WorkspaceFileDifferenceResponse(change.getChange().getKind(), change.getChange().getPath(),
                change.getPreviousContent(), change.getChange().getContent())).toList());
    }
}
