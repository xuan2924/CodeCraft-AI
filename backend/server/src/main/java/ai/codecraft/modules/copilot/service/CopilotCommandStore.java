package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotCommandRecoveryDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotCommandAdmissionDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotCommandResultDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionRequest;
import ai.codecraft.modules.copilot.model.entity.CopilotCommandEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotApprovalModeRepository;
import ai.codecraft.modules.copilot.repository.CopilotCommandRepository;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.identity.model.dto.SessionAuthorizationDTO;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.CommandTaskStore;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Decision, child intent, and parent wait commit atomically; no model, meter, or sandbox calls occur here. */
@Service
public class CopilotCommandStore {
    private final CopilotCommandRepository commands;
    private final CopilotTaskRepository tasks;
    private final CopilotApprovalModeRepository modes;
    private final CopilotExecutionStore execution;
    private final CopilotTaskStore views;
    private final CommandTaskStore children;
    private final CopilotCommandLifecycle lifecycle;
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ExerciseTransactionService exercises;
    private final WorkspaceCoordinationStore workspace;
    private final ObjectMapper json;
    private final Clock clock;

    public CopilotCommandStore(CopilotCommandRepository commands, CopilotTaskRepository tasks, CopilotApprovalModeRepository modes,
        CopilotExecutionStore execution, CopilotTaskStore views, CommandTaskStore children, CopilotCommandLifecycle lifecycle,
        IdentityTransactionService identity, IdentitySecrets secrets, ExerciseTransactionService exercises,
        WorkspaceCoordinationStore workspace, @Qualifier("workspaceJson") ObjectMapper json, Clock clock) {
        this.commands = commands; this.tasks = tasks; this.modes = modes; this.execution = execution; this.views = views;
        this.children = children; this.lifecycle = lifecycle; this.identity = identity; this.secrets = secrets;
        this.exercises = exercises; this.workspace = workspace; this.json = json; this.clock = clock;
    }

    @Transactional
    public boolean register(String taskId, String worker, String toolId, CommandStartRequest input) {
        var task = execution.current(taskId, worker);
        if (task == null || task.stopRequested()) throw invalidState();
        requireUser(identity.authorizeMutation(task.sessionHash()));
        var exercise = exercises.find(task.sessionHash(), task.exerciseId());
        if (ended(exercise)) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        children.validate(input);
        if (exercise.revision() == null || !input.expectedRevision().equals(exercise.revision().toString())) {
            throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
        }
        var tool = tasks.toolById(toolId);
        if (tool == null || !taskId.equals(tool.taskId()) || !"bash".equals(tool.name()) || !"RUNNING".equals(tool.state())) throw invalidState();
        long goal = tasks.goalSequence(taskId);
        if (goal <= 0) throw new IllegalStateException("Command requires a committed user goal");
        String fingerprint = secrets.fingerprint(encode(input));
        if (commands.rejected(taskId, goal, fingerprint)) return false;
        var mode = modes.current(task.exerciseId());
        commands.insert(new CopilotCommandEntity(toolId, 0, taskId, task.exerciseId(), task.userId(), exercise.workspaceInstanceId(),
            Long.parseLong(input.expectedRevision()), goal, input.command(), input.directory(), fingerprint,
            mode == null ? "ASK" : mode.mode(), mode == null ? 0 : mode.version(), "PENDING", null, null, null, null, null, null,
            null, null, clock.instant(), null, null));
        execution.parkCommandApproval(taskId, worker, toolId);
        execution.steerIdle(taskId);
        return true;
    }

    @Transactional
    public List<CopilotCommandEntity> page(String session, String taskId, long before) {
        views.find(session, taskId);
        if (before < 0) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
        return commands.page(taskId, before);
    }

    @Transactional
    public CopilotCommandEntity read(String session, String id) { return owned(session, id); }

    @Transactional
    public CopilotCommandEntity replay(String session, String id, String key, CopilotProposalDecisionRequest input) {
        var command = owned(session, id);
        validate(key, input);
        manual(command);
        return replay(command, key, decisionFingerprint(input));
    }

    @Transactional
    public CopilotCommandEntity decide(String session, String id, String credential, String key,
        CopilotProposalDecisionRequest input, StorageMeasurement measurement, String admissionFailure) {
        var actor = identity.authorizeMutation(session);
        requireUser(actor);
        var command = owned(session, id);
        validate(key, input);
        manual(command);
        String fingerprint = decisionFingerprint(input);
        var previous = replay(command, key, fingerprint);
        if (previous != null) return previous;
        workspace.checkControl(command.exerciseId(), IdentitySecrets.digest(credential));
        var task = tasks.find(command.taskId());
        var exercise = exercises.find(session, command.exerciseId());
        recordDecision(command, key, fingerprint, input.decision(), actor, reason(input));
        if (task.stopRequested() || ended(exercise)) {
            resolve(command, task.stopRequested() ? "TASK_STOPPED" : "EXERCISE_ENDED", "");
        } else if (!"WAITING_APPROVAL".equals(task.state()) || task.workerId() != null) {
            throw invalidState();
        } else if (stale(command, exercise)) {
            resolve(command, "REVISION_CONFLICT", "");
        } else {
            // Only a new explicit decision can renew continuation authority, never polling or receipt replay.
            if (tasks.reauthorizeWaiting(task.id(), session) != 1) throw invalidState();
            if ("REJECT".equals(input.decision())) resolve(command, "COMMAND_REJECTED", reason(input));
            else if (admissionFailure != null) resolve(command, admissionFailure, "");
            else bind(command, session, measurement);
        }
        return commands.find(id);
    }

    @Transactional
    public CopilotCommandAdmissionDTO autoCandidate(String taskId) {
        var command = automatic(taskId);
        return command == null ? null : new CopilotCommandAdmissionDTO(command, tasks.find(taskId).sessionHash());
    }

    @Transactional
    public void approveAuto(String taskId, String expectedId, StorageMeasurement measurement, String admissionFailure) {
        var command = automatic(taskId);
        if (command == null || !command.id().equals(expectedId)) return;
        var task = tasks.find(taskId);
        var actor = identity.authorizeMutation(task.sessionHash());
        requireUser(actor);
        recordDecision(command, "auto:" + command.id(), secrets.fingerprint("AUTO\n" + command.modeVersion()), "APPROVE", actor, "");
        if (admissionFailure != null) resolve(command, admissionFailure, "");
        else bind(command, task.sessionHash(), measurement);
    }

    private CopilotCommandEntity automatic(String taskId) {
        var initial = tasks.find(taskId);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        execution.steerIdle(taskId);
        var command = commands.pending(taskId);
        var task = tasks.find(taskId);
        if (command == null || !"PENDING".equals(command.state()) || !"AUTO".equals(command.mode()) || task.workerId() != null) return null;
        String invalid = invalidPending(task, command);
        if (invalid != null) { resolve(command, invalid, ""); return null; }
        if (!"WAITING_APPROVAL".equals(task.state())) throw invalidState();
        return command;
    }

    private void bind(CopilotCommandEntity command, String session, StorageMeasurement measurement) {
        var child = children.acceptCopilot(session, command.exerciseId(), "copilot-command:" + command.id(),
            new CommandStartRequest(command.commandText(), command.workingDirectory(), Long.toString(command.baseRevision())), measurement);
        if (commands.bind(command.id(), child.id()) != 1) throw new IllegalStateException("Command binding was not committed");
        execution.awaitCommand(command.taskId(), command.id());
    }

    @Transactional
    public CopilotCommandRecoveryDTO recovery(String taskId) {
        var initial = tasks.find(taskId);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(taskId);
        var command = commands.pending(taskId);
        if (command == null || task.workerId() != null) return null;
        String invalid = invalidPending(task, command);
        if ("PENDING".equals(command.state())) {
            if (invalid != null) resolve(command, invalid, "");
            return null;
        }
        if (task.stopRequested() || invalid != null && !"REVISION_CONFLICT".equals(invalid)) lifecycle.stop(taskId);
        var evidence = children.confirmedResult(command.exerciseId(), command.commandTaskId());
        return evidence == null ? null : new CopilotCommandRecoveryDTO(command, evidence);
    }

    @Transactional
    public void complete(CopilotCommandRecoveryDTO source, String payload) {
        var initial = tasks.find(source.command().taskId());
        if (initial == null) return;
        identity.lockResourceOwner(initial.userId());
        var command = commands.pending(initial.id());
        if (command == null || !"WAITING".equals(command.state()) || !command.id().equals(source.command().id())
            || !command.commandTaskId().equals(source.command().commandTaskId())
            || !children.resultUnchanged(command.exerciseId(), command.commandTaskId(), source.evidence())) return;
        var task = tasks.find(command.taskId());
        if (!task.stopRequested() && !authorized(task)) tasks.haltIdle(task.id(), "AUTHORIZATION_ENDED");
        if (commands.resolve(command.id(), payload, clock.instant()) != 1) throw new IllegalStateException("Command result was not committed");
        execution.resolveWaitingTool(command.taskId(), command.id(), payload);
    }

    private String invalidPending(CopilotTaskEntity task, CopilotCommandEntity command) {
        var exercise = workspace.exercise(command.exerciseId());
        if (task.stopRequested()) return "TASK_STOPPED";
        if (ended(exercise)) { tasks.haltIdle(task.id(), "EXERCISE_ENDED"); return "EXERCISE_ENDED"; }
        if (!authorized(task)) { tasks.haltIdle(task.id(), "AUTHORIZATION_ENDED"); return "AUTHORIZATION_ENDED"; }
        return stale(command, exercise) ? "REVISION_CONFLICT" : null;
    }

    private boolean authorized(CopilotTaskEntity task) {
        var grant = identity.tryAuthorizeMutation(task.sessionHash());
        return grant != null && task.userId().equals(grant.userId()) && "USER".equals(grant.role())
            && !grant.passwordChangeRequired() && clock.instant().isBefore(grant.expiresAt());
    }

    private void resolve(CopilotCommandEntity command, String code, String reason) {
        String payload = encode(new CopilotCommandResultDTO(code, reason, null, false, "0"));
        if (commands.resolve(command.id(), payload, clock.instant()) != 1) throw new IllegalStateException("Command decision did not resolve");
        execution.resolveWaitingTool(command.taskId(), command.id(), payload);
    }

    private void recordDecision(CopilotCommandEntity command, String key, String fingerprint, String decision, SessionAuthorizationDTO actor, String reason) {
        if (commands.decide(command.id(), key, fingerprint, decision, actor.userId(), actor.authVersion(), reason, clock.instant()) != 1) {
            throw new BusinessException(CopilotErrorCode.COPILOT_PROPOSAL_DECIDED);
        }
    }
    private CopilotCommandEntity owned(String session, String id) {
        var grant = identity.authorizePrivateDelivery(session);
        requireUser(grant);
        var command = commands.find(id);
        if (command == null || !grant.userId().equals(command.userId())) throw new BusinessException(CopilotErrorCode.COPILOT_PROPOSAL_NOT_FOUND);
        exercises.find(session, command.exerciseId());
        return command;
    }
    private CopilotCommandEntity replay(CopilotCommandEntity command, String key, String fingerprint) {
        if (command.decisionKey() != null) {
            if (!command.decisionKey().equals(key)) throw new BusinessException(CopilotErrorCode.COPILOT_PROPOSAL_DECIDED);
            if (!command.decisionFingerprint().equals(fingerprint)) throw new BusinessException(CopilotErrorCode.COPILOT_IDEMPOTENCY_CONFLICT);
            return command;
        }
        return "PENDING".equals(command.state()) ? null : command;
    }
    private void validate(String key, CopilotProposalDecisionRequest input) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || input == null
            || !("APPROVE".equals(input.decision()) || "REJECT".equals(input.decision()))
            || reason(input).indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(reason(input))
            || reason(input).getBytes(StandardCharsets.UTF_8).length > 2048
            || "APPROVE".equals(input.decision()) && !reason(input).isEmpty()) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_REQUEST);
    }
    private void manual(CopilotCommandEntity command) { if (!"ASK".equals(command.mode())) throw invalidState(); }
    private String reason(CopilotProposalDecisionRequest input) { return input.reason() == null ? "" : input.reason(); }
    private String decisionFingerprint(CopilotProposalDecisionRequest input) { return secrets.fingerprint(input.decision() + "\n" + reason(input)); }
    private boolean ended(ExerciseEntity exercise) {
        return exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt());
    }
    private boolean stale(CopilotCommandEntity command, ExerciseEntity exercise) {
        return !command.workspaceInstanceId().equals(exercise.workspaceInstanceId()) || exercise.revision() == null || command.baseRevision() != exercise.revision();
    }
    private void requireUser(SessionAuthorizationDTO grant) { if (!"USER".equals(grant.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN); }
    private BusinessException invalidState() { return new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode command evidence", error); }
    }
}
