package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.TerminalRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions only. Terminal grants reuse command admission and quota. */
@Service
public class TerminalStore {
    public static final String COMMAND = "exec /bin/bash --noprofile --norc -i";
    private final CommandTaskStore commands;
    private final TerminalRepository tickets;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final WorkspaceWriteRepository writes;
    private final ExerciseRepository exercises;
    private final Clock clock;

    public TerminalStore(CommandTaskStore commands, TerminalRepository tickets, IdentityTransactionService identity,
        IdentitySecrets secrets, WorkspaceWriteRepository writes, ExerciseRepository exercises, Clock clock) {
        this.commands=commands; this.tickets=tickets; this.identity=identity; this.secrets=secrets;
        this.writes=writes; this.exercises=exercises; this.clock=clock;
    }

    public record SessionResponse(String commandId, String ticket, String webSocketPath, String protocol, String gateEpoch, Instant expiresAt) {
        @Override public String toString() { return "TerminalTicket[commandId="+commandId+", ticket=<redacted>]"; }
    }

    @Transactional
    public SessionResponse issue(String session, String exercise, String credential, String key, String revision, StorageMeasurement measurement) {
        var actor=identity.authorizeMutation(session);
        if (!"USER".equals(actor.role())) fail(WorkspaceErrorCode.TERMINAL_AUTHORITY_ENDED);
        var task=commands.accept(session,exercise,key,credential,new CommandStartRequest(COMMAND,".",revision),measurement);
        String token=secrets.token();
        Instant expiry=clock.instant().plusSeconds(30);
        if (task.notAfter().isBefore(expiry)) expiry=task.notAfter();
        if (tickets.ticket(task.id(),IdentitySecrets.digest(token),expiry)!=1) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
        return new SessionResponse(task.id(),token,"/api/exercises/"+exercise+"/terminal/ws","codecraft.terminal.v1",Long.toString(task.gateEpoch()),expiry);
    }

    @Transactional
    public CommandTaskEntity consume(String session, String exercise, String token) {
        var actor=identity.authorizeMutation(session);
        String hash=IdentitySecrets.digest(token);
        if (hash==null) fail(WorkspaceErrorCode.TERMINAL_TICKET_INVALID);
        String task=tickets.lookup(hash,exercise,session,actor.userId());
        if (task==null) fail(WorkspaceErrorCode.TERMINAL_TICKET_INVALID);
        writes.lock(exercise);
        var command=authorize(session,exercise,task);
        if (tickets.consume(task,clock.instant())!=1) fail(WorkspaceErrorCode.TERMINAL_TICKET_INVALID);
        return command;
    }

    // Identity authorization acquires an owner row lock; PostgreSQL read-only transactions forbid it.
    @Transactional
    public CommandTaskEntity authorize(String session, String exercise, String taskId) {
        var actor=identity.authorizeMutation(session);
        var task=commands.read(session,exercise,taskId);
        var control=writes.control(exercise);
        var current=exercises.find(exercise);
        if (!commands.terminal(taskId)||!"USER".equals(actor.role())||!Objects.equals(session,task.sessionHash())
            ||actor.authVersion()!=task.actorAuthVersion()||!actor.userId().equals(task.userId())
            ||control==null||current==null||!"ACTIVE".equals(current.state())||!"OPEN".equals(control.gateState())
            ||!Objects.equals(current.workspaceInstanceId(),task.workspaceInstanceId())||current.revision()==null||current.revision()!=task.revision()
            ||control.gateEpoch()==null||control.gateEpoch()!=task.gateEpoch()||control.fenceId()!=null||control.transitionId()!=null
            ||!clock.instant().isBefore(task.notAfter())||task.stopRequested()||task.quiescent()) fail(WorkspaceErrorCode.TERMINAL_AUTHORITY_ENDED);
        return task;
    }

    @Transactional
    public void close(String task) { commands.requestSystemStop(task); tickets.closed(task,clock.instant()); }

    @Transactional
    public List<ai.codecraft.modules.workspace.model.dto.TerminalAuditResponse> history(String session, String exercise) {
        var actor=identity.authorizePrivateDelivery(session);
        var current=exercises.find(exercise);
        if(current==null||!actor.userId().equals(current.userId())||exercises.deleted(exercise)) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        return tickets.history(exercise);
    }

    @Transactional(readOnly = true)
    public List<String> abandoned() { return tickets.abandoned(clock.instant()); }

    private static void fail(WorkspaceErrorCode code) { throw new BusinessException(code); }
}
