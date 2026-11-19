package ai.codecraft.modules.workspace.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.infrastructure.stream.TransientTaskStreamHub;
import ai.codecraft.infrastructure.tasks.TaskWakeup;
import ai.codecraft.infrastructure.tasks.TaskWakeupPublisher;
import ai.codecraft.modules.identity.service.IdentitySecrets;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.platformagent.model.PlatformRole;
import ai.codecraft.modules.platformagent.service.PlatformBindingService;
import ai.codecraft.modules.workspace.model.dto.InterviewerDispatchDTO;
import ai.codecraft.modules.workspace.model.dto.InterviewerRequest;
import ai.codecraft.modules.workspace.model.dto.InterviewerTaskResponse;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.entity.InterviewerTaskEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.InterviewerRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner/control-serialized task admission and publication. Never calls a model under a transaction. */
@Service
public class InterviewerStore {
    private final InterviewerRepository tasks;
    private final ExerciseRepository exercises;
    private final IdentityTransactionService identity;
    private final WorkspaceCoordinationStore coordination;
    private final PlatformBindingService platform;
    private final StorageCapacityStore capacity;
    private final IdentitySecrets secrets;
    private final Clock clock;
    private final TaskWakeupPublisher wakeups;
    private final TransientTaskStreamHub streams;

    public InterviewerStore(InterviewerRepository tasks, ExerciseRepository exercises, IdentityTransactionService identity,
        WorkspaceCoordinationStore coordination, PlatformBindingService platform,
        StorageCapacityStore capacity, IdentitySecrets secrets, Clock clock, TaskWakeupPublisher wakeups, TransientTaskStreamHub streams) {
        this.tasks = tasks; this.exercises = exercises; this.identity = identity; this.coordination = coordination;
        this.platform = platform; this.capacity = capacity; this.secrets = secrets; this.clock = clock; this.wakeups = wakeups; this.streams = streams;
    }

    @Transactional
    public InterviewerTaskResponse existing(String session, String exercise, String key, String question) {
        return existing(session, exercise, key, new InterviewerRequest(question));
    }

    @Transactional
    public InterviewerTaskResponse existing(String session, String exercise, String key, InterviewerRequest input) {
        owned(session, exercise);
        validate(key, input);
        var previous = tasks.receipt(exercise, key);
        if (previous != null && !same(previous, input)) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
        return previous == null ? null : describe(previous);
    }

    @Transactional
    public InterviewerTaskResponse accept(String session, String id, String key, String credential, String question, StorageMeasurement measurement) {
        return accept(session, id, key, credential, new InterviewerRequest(question), measurement);
    }

    @Transactional
    public InterviewerTaskResponse accept(String session, String id, String key, String credential, InterviewerRequest input, StorageMeasurement measurement) {
        var auth = identity.authorizeMutation(session);
        var exercise = requireOwner(auth.userId(), id);
        validate(key, input);
        var previous = tasks.receipt(id, key);
        if (previous != null) {
            if (!same(previous, input)) fail(WorkspaceErrorCode.IDEMPOTENCY_CONFLICT);
            return describe(previous);
        }
        coordination.checkControl(id, IdentitySecrets.digest(credential));
        exercise = exercises.find(id);
        if (!answering(exercise) || "DEFENSE".equals(input.mode()) && !"ACTIVE".equals(exercise.state())) fail(WorkspaceErrorCode.INVALID_STATE);
        if (tasks.active(id) || tasks.count(id) >= 200) fail(WorkspaceErrorCode.INVALID_STATE);
        var version = platform.version(id, PlatformRole.INTERVIEWER);
        String taskId = UUID.randomUUID().toString();
        var operation = new WorkspaceOperationEntity(taskId, auth.userId(), id, exercise.workspaceInstanceId(), "INTERVIEWER", id,
            key, secrets.fingerprint(input.mode() + ":" + input.topic() + ":" + input.text()), "PENDING", clock.instant());
        capacity.admit(operation, measurement, 131072);
        exercises.insertOperation(operation);
        var task = new InterviewerTaskEntity(taskId, id, auth.userId(), session, IdentitySecrets.digest(credential), key, input.text(),
            null, "QUEUED", null, version.id(), exercise.state(), stage(id), input.mode(), input.topic(),
            "HINT".equals(input.mode()) ? Math.min(3, tasks.successfulHints(id, input.topic()) + 1) : 1, exercise.revision(), clock.instant(),
            earlier(clock.instant().plusSeconds(300), auth.expiresAt()), null, null);
        tasks.insert(task);
        wakeups.afterCommit(TaskWakeup.Lane.INTERVIEWER);
        streams.afterCommit(task.id());
        return describe(task);
    }

    @Transactional
    public List<InterviewerTaskResponse> history(String session, String exercise, String before) {
        owned(session, exercise);
        return tasks.history(exercise, before).stream().map(this::describe).toList();
    }

    @Transactional
    public InterviewerTaskResponse find(String session, String exercise, String id) {
        owned(session, exercise);
        var task = tasks.find(id);
        if (task == null || !exercise.equals(task.exerciseId())) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        return describe(task);
    }

    @Transactional(readOnly = true)
    public List<String> pending() { return tasks.pending(clock.instant()); }

    @Transactional
    public InterviewerDispatchDTO claim(String id) {
        var task = tasks.find(id);
        if (task == null) return null;
        identity.lockResourceOwner(task.userId());
        task = tasks.find(id);
        if (!List.of("QUEUED", "RUNNING").contains(task.state())) return null;
        if (!clock.instant().isBefore(task.deadlineAt())) {
            complete(task, null, "RUNNING".equals(task.state()) ? "INTERVIEWER_RESULT_UNKNOWN" : "INTERVIEWER_QUEUE_EXPIRED", null);
            return null;
        }
        if ("RUNNING".equals(task.state())) return null;
        if (!authorized(task)) { complete(task, null, "INTERVIEWER_INTERRUPTED", null); return null; }
        var exercise = exercises.find(task.exerciseId());
        var version = platform.version(task.exerciseId(), PlatformRole.INTERVIEWER);
        if (!version.id().equals(task.platformVersionId())) { complete(task, null, "INTERVIEWER_INTERRUPTED", null); return null; }
        var auth = identity.tryAuthorizeMutation(task.sessionHash());
        Instant deadline = earlier(clock.instant().plusSeconds(120), auth.expiresAt());
        if (exercise.deadlineAt() != null) deadline = earlier(deadline, exercise.deadlineAt());
        if (tasks.claim(id, deadline) != 1) return null;
        streams.afterCommit(id);
        return new InterviewerDispatchDTO(id, task.exerciseId(), task.question(), task.phase(), task.stage(), exercise.revision(), task.mode(), task.topic(), task.hintLevel(), task.sessionHash(), version, deadline);
    }

    @Transactional
    public void rejectQueued(String id, String reason) {
        var task = tasks.find(id);
        if (task == null) return;
        identity.lockResourceOwner(task.userId());
        task = tasks.find(id);
        if (task != null && "QUEUED".equals(task.state())) complete(task, null, reason, null);
    }

    @Transactional
    public boolean recordPrice(String id, String price) {
        var task = tasks.find(id);
        if (task == null) return false;
        identity.lockResourceOwner(task.userId());
        task = tasks.find(id);
        if (!"RUNNING".equals(task.state()) || !clock.instant().isBefore(task.deadlineAt()) || !authorized(task)) return false;
        return tasks.price(id, price) == 1;
    }

    @Transactional
    public void finish(String id, String answer, String error, String usage) {
        var task = tasks.find(id);
        if (task == null) return;
        identity.lockResourceOwner(task.userId());
        task = tasks.find(id);
        if (!"RUNNING".equals(task.state())) return;
        if (!clock.instant().isBefore(task.deadlineAt()) || !authorized(task)) error = "INTERVIEWER_INTERRUPTED";
        if (error == null && (answer == null || answer.isBlank() || answer.getBytes(StandardCharsets.UTF_8).length > 16384)) error = "INTERVIEWER_INVALID_RESPONSE";
        complete(task, error == null ? answer : null, error, usage);
    }

    private boolean authorized(InterviewerTaskEntity task) {
        var auth = identity.tryAuthorizeMutation(task.sessionHash());
        var exercise = exercises.find(task.exerciseId());
        if (auth == null || !auth.userId().equals(task.userId()) || exercises.deleted(task.exerciseId())
            || !answering(exercise) || !task.phase().equals(exercise.state())) return false;
        try { coordination.checkControl(task.exerciseId(), task.controlHash()); }
        catch (BusinessException stale) { return false; }
        // Refresh after the control lock to serialize with pause, end and takeover.
        exercise = exercises.find(task.exerciseId());
        return answering(exercise) && task.phase().equals(exercise.state()) && task.stage().equals(stage(task.exerciseId()));
    }

    private void complete(InterviewerTaskEntity task, String answer, String error, String usage) {
        tasks.finish(task.id(), answer, error, clock.instant(), usage);
        streams.afterCommit(task.id());
        exercises.operationState(task.id(), error == null ? "SUCCEEDED" : "REJECTED");
    }

    private void owned(String session, String id) { requireOwner(identity.authorizePrivateDelivery(session).userId(), id); }
    private ExerciseEntity requireOwner(String user, String id) {
        var exercise = exercises.find(id);
        if (exercise == null || !user.equals(exercise.userId())) fail(WorkspaceErrorCode.RESOURCE_NOT_FOUND);
        if (exercises.deleted(id)) fail(WorkspaceErrorCode.EXERCISE_DELETED);
        return exercise;
    }
    private boolean answering(ExerciseEntity exercise) {
        return exercise != null && ("READY".equals(exercise.state()) || "ACTIVE".equals(exercise.state())
            && exercise.deadlineAt() != null && clock.instant().isBefore(exercise.deadlineAt()));
    }
    private String stage(String exercise) { return exercises.challengeEntered(exercise) ? "CHALLENGE" : "BASE"; }
    private boolean same(InterviewerTaskEntity task, InterviewerRequest input) {
        return task.question().equals(input.text()) && task.mode().equals(input.mode()) && task.topic().equals(input.topic());
    }
    private void validate(String key, InterviewerRequest input) {
        String question = input.text();
        if (!List.of("CLARIFY", "HINT", "DEFENSE").contains(input.mode()) || !input.topic().matches("[A-Za-z0-9_-]{1,64}")) fail(WorkspaceErrorCode.INVALID_REQUEST);
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || question == null || question.isBlank()
            || question.getBytes(StandardCharsets.UTF_8).length > 4096 || question.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) fail(WorkspaceErrorCode.INVALID_REQUEST);
    }
    private static Instant earlier(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private static void fail(WorkspaceErrorCode code) { throw new BusinessException(code); }
    private InterviewerTaskResponse describe(InterviewerTaskEntity task) {
        return new InterviewerTaskResponse(task.id(), task.exerciseId(), task.question(), task.answer(), task.state(), task.reason(), task.platformVersionId(),
            task.phase(), task.stage(), task.mode(), task.topic(), task.hintLevel(), task.revision() == null ? null : task.revision().toString(), task.createdAt(), task.completedAt());
    }
}
