package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.workspace.gateway.SandboxWorkspaceGateway;
import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import org.springframework.stereotype.Service;

@Service
public class TerminalService {
    private final TerminalStore store;
    private final StorageCapacityService capacity;
    private final WorkspaceWriteTransactionService writes;
    private final WorkspaceWriteCoordinator fences;
    private final CommandTaskStore commands;
    private final CommandTaskCoordinator coordinator;
    private final SandboxWorkspaceGateway sandbox;
    public TerminalService(TerminalStore store,StorageCapacityService capacity,WorkspaceWriteTransactionService writes,
        WorkspaceWriteCoordinator fences,CommandTaskStore commands,CommandTaskCoordinator coordinator,SandboxWorkspaceGateway sandbox){
        this.store=store;this.capacity=capacity;this.writes=writes;this.fences=fences;this.commands=commands;this.coordinator=coordinator;this.sandbox=sandbox;
    }
    public TerminalStore.SessionResponse issue(String session,String exercise,String credential,String key,String revision){
        if(revision==null||!revision.matches("0|[1-9][0-9]{0,17}"))throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        String transition=writes.ensureOpen(session,exercise,credential);
        if(transition!=null)fences.tryDrive(transition);
        return capacity.admitHeavy(session,exercise,measurement->store.issue(session,exercise,credential,key,revision,measurement));
    }
    public java.util.List<ai.codecraft.modules.workspace.model.dto.TerminalAuditResponse> history(String session,String exercise) { return store.history(session,exercise); }

    public CommandTaskResponse sync(String session,String exercise,String task){
        var command=commands.read(session,exercise,task);
        if(!commands.terminal(task))throw new BusinessException(WorkspaceErrorCode.INVALID_REQUEST);
        if(!command.quiescent()){
            command=store.authorize(session,exercise,task);
            if(!command.dispatched())throw new BusinessException(WorkspaceErrorCode.TERMINAL_BUSY);
            try {
                sandbox.syncTerminal(command);
                coordinator.observe(command);
            } catch (io.grpc.StatusRuntimeException failure) {
                var code=switch(failure.getStatus().getCode()) {
                    case ABORTED -> WorkspaceErrorCode.REVISION_CONFLICT;
                    case FAILED_PRECONDITION, PERMISSION_DENIED -> WorkspaceErrorCode.TERMINAL_AUTHORITY_ENDED;
                    case NOT_FOUND, RESOURCE_EXHAUSTED -> WorkspaceErrorCode.TERMINAL_BUSY;
                    default -> WorkspaceErrorCode.WORKSPACE_UNAVAILABLE;
                };
                throw new BusinessException(code,failure);
            }
        }
        return commands.response(commands.read(session,exercise,task));
    }
}
