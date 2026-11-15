package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.CommandStartRequest;
import ai.codecraft.modules.workspace.model.dto.CommandEvidenceDTO;
import ai.codecraft.modules.workspace.model.dto.CommandHistoryResponse;
import ai.codecraft.modules.workspace.model.dto.CommandTaskResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.StorageExecutionEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.CommandTaskRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.ProblemRepository;
import ai.codecraft.modules.workspace.repository.TestTaskRepository;
import ai.codecraft.modules.workspace.repository.WorkspaceWriteRepository;
import ai.codecraft.workspace.v1.CommandSnapshot;
import ai.codecraft.workspace.v1.WorkspaceSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Durable business intent only; external execution and measurement stay outside transactions. */
@Service
public class CommandTaskStore {
    private final IdentityTransactionService identity;
    private final IdentitySecrets secrets;
    private final ExerciseRepository exercises;
    private final WorkspaceWriteRepository writes;
    private final TestTaskRepository admission;
    private final CommandTaskRepository commands;
    private final ProblemRepository problems;
    private final StorageCapacityStore capacity;
    private final ObjectMapper json;
    private final Clock clock;
    private final ExerciseDataService data;
    private final WorkspaceHistoryLedger history;

    public CommandTaskStore(IdentityTransactionService identity, IdentitySecrets secrets, ExerciseRepository exercises,
        WorkspaceWriteRepository writes, TestTaskRepository admission, CommandTaskRepository commands, ProblemRepository problems,
        StorageCapacityStore capacity, @Qualifier("workspaceJson") ObjectMapper json, Clock clock, ExerciseDataService data, WorkspaceHistoryLedger history) {
        this.identity = identity; this.secrets = secrets; this.exercises = exercises; this.writes = writes; this.admission = admission;
        this.commands = commands; this.problems = problems; this.capacity = capacity; this.json = json; this.clock = clock; this.data = data; this.history = history;
    }

    @Transactional
    public CommandTaskEntity previous(String session, String exercise, String key, CommandStartRequest input) {
        owned(identity.authorizePrivateDelivery(session).userId(), exercise);
        return receipt(exercise, key, fingerprint(key, input));
    }

    @Transactional
    public CommandTaskEntity accept(String session, String exerciseId, String key, String credential, CommandStartRequest input, StorageMeasurement measurement) {
        return accept(session, exerciseId, key, credential, input, measurement, false);
    }

    /** Parent binding must commit in the caller's transaction; never adopts an existing manual receipt. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CommandTaskEntity acceptCopilot(String session, String exerciseId, String key, CommandStartRequest input, StorageMeasurement measurement) {
        return accept(session, exerciseId, key, null, input, measurement, true);
    }

    private CommandTaskEntity accept(String session, String exerciseId, String key, String credential, CommandStartRequest input, StorageMeasurement measurement, boolean background) {
        var actor = identity.authorizeMutation(session);
        if (background && !"USER".equals(actor.role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        var exercise = owned(actor.userId(), exerciseId);
        var control = writes.lock(exerciseId);
        String fingerprint = fingerprint(key, input);
        var previous = receipt(exerciseId, key, fingerprint);
        if (previous != null) {
            if (background) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            return previous;
        }
        if (control == null) fail(background ? WorkspaceErrorCode.WORKSPACE_UNAVAILABLE : WorkspaceErrorCode.STALE_CONTROL);
        String hash = IdentitySecrets.digest(credential);
        if (!background && (hash == null || !hash.equals(control.credentialHash()))) fail(WorkspaceErrorCode.STALE_CONTROL);
        if (!exercise.state().equals("ACTIVE")) fail(WorkspaceErrorCode.INVALID_STATE);
        if (!clock.instant().isBefore(exercise.deadlineAt())) fail(WorkspaceErrorCode.EXERCISE_EXPIRED);
        if (control.transitionId() != null || control.fenceId() != null) fail(WorkspaceErrorCode.CONTROL_TRANSITION_PENDING);
        if (!control.gateState().equals("OPEN") || control.gateEpoch() == null) fail(WorkspaceErrorCode.WORKSPACE_UNAVAILABLE);
        long revision = Long.parseLong(input.expectedRevision());
        if (exercise.revision() == null || exercise.revision() != revision) fail(WorkspaceErrorCode.REVISION_CONFLICT);
        if (!problems.executionAvailable(exercise.problemVersionId())) fail(WorkspaceErrorCode.COMMAND_UNAVAILABLE);
        admission.lockAdmission();
        if (admission.occupied() != 0) fail(WorkspaceErrorCode.COMMAND_SLOT_BUSY);
        var now = clock.instant();
        var notAfter = actor.expiresAt().isBefore(exercise.deadlineAt()) ? actor.expiresAt() : exercise.deadlineAt();
        String id = UUID.randomUUID().toString();
        var operation = new WorkspaceOperationEntity(id, actor.userId(), exerciseId, exercise.workspaceInstanceId(),
            "START_COMMAND", exerciseId, key, fingerprint, "PENDING", now);
        capacity.admit(operation, measurement, StorageCapacityStore.COMMAND_GROWTH);
        exercises.insertOperation(operation);
        var task = new CommandTaskEntity(id, exerciseId, actor.userId(), exercise.workspaceInstanceId(), UUID.randomUUID().toString(),
            session, actor.authVersion(), key, fingerprint, input.command(), input.directory(), revision, control.gateEpoch(), notAfter,
            null, "ACCEPTED", false, false, false, "PENDING", null, 0, false, false, now, null);
        commands.insert(task);
        capacity.registerExecution(new StorageExecutionEntity(exerciseId, id, task.executionInstanceId(), task.workspaceInstanceId()));
        return task;
    }

    @Transactional
    public CommandTaskEntity read(String session, String exercise, String id) {
        owned(identity.authorizePrivateDelivery(session).userId(), exercise);
        var task = commands.find(id);
        if (task == null || !task.exerciseId().equals(exercise)) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (task.pruning() || task.pruned()) fail(WorkspaceErrorCode.COMMAND_HISTORY_PRUNED);
        return task;
    }

    @Transactional
    public CommandHistoryResponse recent(String session, String exercise, String after) {
        owned(identity.authorizePrivateDelivery(session).userId(), exercise);
        if (after != null) {
            var anchor = commands.find(after);
            if (anchor == null || !anchor.exerciseId().equals(exercise)) fail(WorkspaceErrorCode.INVALID_REQUEST);
        }
        var page = commands.recent(exercise, after);
        return new CommandHistoryResponse(page.stream().limit(20).map(this::response).toList(), page.size() > 20 ? page.get(19).id() : null);
    }

    @Transactional
    public void stop(String session, String exercise, String id, String credential) {
        var owner = owned(identity.authorizeMutation(session).userId(), exercise);
        var control = writes.lock(exercise);
        var task = commands.lock(id);
        if (task == null || !task.exerciseId().equals(exercise)) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (task.pruning() || task.pruned()) fail(WorkspaceErrorCode.COMMAND_HISTORY_PRUNED);
        if (task.stopRequested() || task.quiescent()) return;
        if (owner.state().equals("ACTIVE")) {
            String hash = IdentitySecrets.digest(credential);
            if (control == null || hash == null || !hash.equals(control.credentialHash())) fail(WorkspaceErrorCode.STALE_CONTROL);
        }
        commands.stop(id);
    }

    @Transactional(readOnly = true)
    public List<CommandTaskEntity> pending() { return commands.pending(); }

    @Transactional(readOnly = true)
    public CommandTaskEntity find(String id) { return commands.find(id); }

    @Transactional(readOnly = true)
    public List<CommandTaskEntity> evictionCandidates() { return commands.evictionCandidates(); }

    @Transactional(readOnly = true)
    public List<CommandTaskEntity> pressureCandidates(String exercise) { return commands.pressureCandidates(exercise); }

    @Transactional
    public boolean beginPruning(String id) {
        var task = lock(id);
        if (data.protectedCommandRecords(task.exerciseId()).contains(id)) {
            commands.checked(id, clock.instant());
            return false;
        }
        return !task.pruned() && commands.beginPruning(id, clock.instant()) == 1;
    }

    @Transactional
    public void pruned(String id) {
        var task = lock(id);
        if (!task.pruned() && !task.pruning()) throw new IllegalStateException("Command pruning was not admitted");
        commands.pruned(id);
    }

    /** A false result never permits redispatch; a changed grant asks for cancellation. */
    @Transactional
    public boolean claim(String id, String hostEpoch) {
        var task = lock(id);
        if (task.pruned() || task.quiescent()) return false;
        commands.checked(id, clock.instant());
        var exercise = exercises.find(task.exerciseId());
        var control = writes.control(task.exerciseId());
        if (!exercise.state().equals("ACTIVE") || !clock.instant().isBefore(task.notAfter())
            || control == null || !"OPEN".equals(control.gateState()) || control.gateEpoch() == null || control.gateEpoch() != task.gateEpoch()
            || control.transitionId() != null || control.fenceId() != null) {
            commands.stop(id);
            return false;
        }
        var actor = identity.tryAuthorizeMutation(task.sessionHash());
        if (actor == null || actor.authVersion() != task.actorAuthVersion() || !actor.userId().equals(task.userId())) {
            commands.stop(id);
            return false;
        }
        if (task.stopRequested() || task.dispatched()) return false;
        if (hostEpoch == null || !hostEpoch.matches("[A-Z2-7]{26}")) throw new IllegalStateException("Invalid command dispatch generation");
        return commands.dispatch(id, hostEpoch, clock.instant()) == 1;
    }

    @Transactional
    public void observe(String id, CommandSnapshot snapshot, WorkspaceSnapshot workspace) {
        var task = lock(id);
        if (task.pruning() || task.pruned()) return;
        if (!task.dispatched()) throw new IllegalStateException("Command was not dispatched");
        var exercise = exercises.find(task.exerciseId());
        var result = CommandSnapshotProjection.project(task, snapshot, problems.find(exercise.problemVersionId()));
        long version = Long.parseLong(result.stateVersion());
        if (version < task.stateVersion()) return;
        if (result.changed()) {
            if (workspace == null || !workspace.getWorkspaceInstanceId().equals(task.workspaceInstanceId())
                || workspace.getRevision() < Long.parseLong(result.synchronizedRevision()) || workspace.getPathsCount() > 200) {
                throw new IllegalStateException("Command revision has not been reconciled");
            }
            history.command(task, Long.parseLong(result.synchronizedRevision()));
            long synchronizedRevision = Long.parseLong(result.synchronizedRevision());
            if (exercise.revision() == null || exercise.revision() < synchronizedRevision) {
                if (workspace.getRevision() != synchronizedRevision) throw new IllegalStateException("Unrelated workspace revision cannot publish command history");
                writes.observed(exercise.id(), synchronizedRevision, encode(workspace.getPathsList()));
            }
        }
        save(task, result);
    }

    /** Trusted cancellation only: an unavailable grant must not keep an old command running. */
    @Transactional
    public void requestSystemStop(String id) {
        var task = lock(id);
        if (!task.pruned()) commands.stop(id);
    }

    /** Stops only the exact child identity supplied by a trusted, persisted parent binding. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestBoundStop(String exerciseId, String id) {
        var task = lock(id);
        if (!task.exerciseId().equals(exerciseId)) throw new IllegalStateException("Command binding belongs to another exercise");
        if (!task.pruned()) commands.stop(id);
    }

    /** Read-only recovery cannot dispatch execution; unresolved synchronization/cleanup has no receipt. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CommandEvidenceDTO confirmedResult(String exerciseId, String id) {
        return commands.confirmed(exerciseId, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resultUnchanged(String exerciseId, String id, CommandEvidenceDTO expected) {
        return expected != null && expected.equals(commands.confirmed(exerciseId, id));
    }

    /** Requires a durable exact-identity cancellation receipt, not a missing query result. */
    @Transactional
    public void cancelledBeforeRegistration(String id) {
        var task = lock(id);
        if (task.pruned() || task.quiescent()) return;
        var result = new CommandTaskResponse(task.id(), task.exerciseId(), task.executionInstanceId(), task.workspaceInstanceId(), Long.toString(task.revision()),
            task.commandText(), task.workingDirectory(), "STOPPED", "CANCELLED_BEFORE_START", task.stopRequested(), true,
            "UNCHANGED", "", null, false, null, List.of(), "0", false, "SUCCEEDED", "SUCCEEDED",
            Long.toString(Math.addExact(task.stateVersion(), 1)), task.createdAt(), clock.instant());
        save(task, result);
    }

    public CommandTaskResponse response(CommandTaskEntity task) {
        if (task.snapshot() != null) {
            try { return json.readValue(task.snapshot(), CommandTaskResponse.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Invalid command checkpoint", error); }
        }
        return new CommandTaskResponse(task.id(), task.exerciseId(), task.executionInstanceId(), task.workspaceInstanceId(), Long.toString(task.revision()),
            task.commandText(), task.workingDirectory(), task.state(), "", task.stopRequested(), task.quiescent(), task.synchronization(), "", null, false,
            null, List.of(), "0", false, "PENDING", "PENDING", Long.toString(task.stateVersion()), task.createdAt(), task.completedAt());
    }

    private void save(CommandTaskEntity task, CommandTaskResponse result) {
        commands.observed(task.id(), result.state(), result.quiescent(), result.synchronization(), encode(result), Long.parseLong(result.stateVersion()), result.completedAt(), clock.instant());
        if (result.quiescent() && !result.synchronization().equals("PENDING") && result.cleanup().equals("SUCCEEDED") && result.copyCleanup().equals("SUCCEEDED")) {
            exercises.operationState(task.id(), "SUCCEEDED");
        }
    }

    private CommandTaskEntity lock(String id) {
        var task = commands.find(id);
        if (task == null) throw new IllegalStateException("Command intent is missing");
        identity.lockResourceOwner(task.userId());
        writes.lock(task.exerciseId());
        return commands.lock(id);
    }

    private CommandTaskEntity receipt(String exercise, String key, String fingerprint) {
        var previous = commands.receipt(exercise, key);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            if (previous.pruning() || previous.pruned()) fail(WorkspaceErrorCode.COMMAND_HISTORY_PRUNED);
        }
        return previous;
    }

    private ExerciseEntity owned(String user, String id) {
        var exercise = exercises.find(id);
        if (exercise == null || !exercise.userId().equals(user)) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(id)) fail(WorkspaceErrorCode.EXERCISE_DELETED);
        return exercise;
    }

    /** Shares the manual command contract with internal tool adapters before durable proposal registration. */
    public void validate(CommandStartRequest input) { fingerprint("validation", input); }

    private String fingerprint(String key, CommandStartRequest input) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || input == null || input.command() == null || input.command().isBlank()
            || !validText(input.command(), 4096) || input.directory() == null || input.directory().isEmpty() || !validText(input.directory(), 1024)
            || input.directory().contains("\\") || input.directory().contains("\r") || input.directory().contains("\n")
            || (!input.directory().equals(".") && Arrays.stream(input.directory().split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
            || input.expectedRevision() == null || !input.expectedRevision().matches("0|[1-9][0-9]{0,18}")) fail(WorkspaceErrorCode.INVALID_REQUEST);
        try { Long.parseLong(input.expectedRevision()); }
        catch (NumberFormatException error) { fail(WorkspaceErrorCode.INVALID_REQUEST); }
        return secrets.fingerprint(encode(input));
    }

    private static boolean validText(String value, int limit) {
        return value.length() <= limit && value.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(value) && value.getBytes(StandardCharsets.UTF_8).length <= limit;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot encode command checkpoint", error); }
    }

    private static void fail(WorkspaceErrorCode code) { throw new BusinessException(code); }
}
