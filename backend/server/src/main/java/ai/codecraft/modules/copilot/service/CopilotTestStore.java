package ai.codecraft.modules.copilot.service;

import ai.codecraft.common.exception.BusinessException;
import ai.codecraft.modules.copilot.model.dto.CopilotTestInputDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotTestRecoveryDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotTestResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTestRunEntity;
import ai.codecraft.modules.copilot.model.exception.CopilotErrorCode;
import ai.codecraft.modules.copilot.repository.CopilotTaskRepository;
import ai.codecraft.modules.copilot.repository.CopilotTestRepository;
import ai.codecraft.modules.identity.model.exception.IdentityErrorCode;
import ai.codecraft.modules.identity.model.exception.IdentityFailure;
import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.StorageMeasurement;
import ai.codecraft.modules.workspace.model.dto.StartTestRequest;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.exception.WorkspaceErrorCode;
import ai.codecraft.modules.workspace.service.ExerciseTransactionService;
import ai.codecraft.modules.workspace.service.TestTaskStore;
import ai.codecraft.modules.workspace.service.WorkspaceCoordinationStore;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admission and parent binding commit together; neither recovery nor result projection can start execution. */
@Service
public class CopilotTestStore {
    private final CopilotTestRepository tests;
    private final CopilotTaskRepository tasks;
    private final CopilotExecutionStore execution;
    private final CopilotTaskStore views;
    private final CopilotTestLifecycle lifecycle;
    private final TestTaskStore children;
    private final IdentityTransactionService identity;
    private final ExerciseTransactionService exercises;
    private final WorkspaceCoordinationStore workspace;
    private final Clock clock;

    public CopilotTestStore(CopilotTestRepository tests, CopilotTaskRepository tasks, CopilotExecutionStore execution,
        CopilotTaskStore views, CopilotTestLifecycle lifecycle, TestTaskStore children, IdentityTransactionService identity,
        ExerciseTransactionService exercises, WorkspaceCoordinationStore workspace, Clock clock) {
        this.tests = tests; this.tasks = tasks; this.execution = execution; this.views = views; this.lifecycle = lifecycle;
        this.children = children; this.identity = identity; this.exercises = exercises; this.workspace = workspace; this.clock = clock;
    }

    @Transactional
    public String cached(String taskId, String worker, CopilotTestInputDTO input) {
        var task = authorized(taskId, worker, input);
        var previous = tests.previous(task.id(), goal(task.id()), Long.parseLong(input.expectedRevision()), input.target());
        if (previous == null) return null;
        if (!"RESOLVED".equals(previous.state())) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        return previous.resultPayload();
    }

    @Transactional
    public void start(String taskId, String worker, String toolId, CopilotTestInputDTO input, StorageMeasurement measurement) {
        var task = authorized(taskId, worker, input);
        var tool = tasks.toolById(toolId);
        if (tool == null || !taskId.equals(tool.taskId()) || !"run_tests".equals(tool.name()) || !"RUNNING".equals(tool.state())) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        }
        long goal = goal(taskId);
        if (tests.previous(taskId, goal, Long.parseLong(input.expectedRevision()), input.target()) != null) {
            throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        }
        var child = children.acceptCopilot(task.sessionHash(), task.exerciseId(), "copilot-test:" + toolId,
            new StartTestRequest(input.expectedRevision(), input.target()), measurement);
        tests.insert(new CopilotTestRunEntity(toolId, taskId, task.exerciseId(), child.workspaceInstanceId(), child.id(), goal,
            child.revision(), child.target(), "WAITING", null, null, null, clock.instant(), null));
        execution.parkTest(taskId, worker, toolId);
    }

    @Transactional
    public CopilotTestRecoveryDTO recovery(String taskId) {
        var initial = tasks.find(taskId);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        var task = tasks.find(taskId);
        var child = tests.pending(taskId);
        if (child == null || task.workerId() != null) return null;
        boolean ended = ended(workspace.exercise(task.exerciseId()));
        boolean authorized = authorized(task);
        if (!task.stopRequested() && (ended || !authorized)) tasks.haltIdle(taskId, ended ? "EXERCISE_ENDED" : "AUTHORIZATION_ENDED");
        if (task.stopRequested() || ended || !authorized) lifecycle.stop(taskId);
        var snapshot = children.confirmedResult(task.exerciseId(), child.testTaskId());
        if (snapshot == null) return null;
        return new CopilotTestRecoveryDTO(child, snapshot);
    }

    @Transactional
    public void complete(CopilotTestRecoveryDTO source, String payload) {
        var initial = tasks.find(source.binding().taskId());
        if (initial == null) return;
        identity.lockResourceOwner(initial.userId());
        var child = tests.pending(source.binding().taskId());
        if (child == null || !child.toolId().equals(source.binding().toolId()) || !child.testTaskId().equals(source.binding().testTaskId())) return;
        if (!children.resultUnchanged(child.exerciseId(), child.testTaskId(), source.evidence().version())) return;
        if (tests.resolved(child.toolId(), source.evidence().state(), source.evidence().version(), payload, clock.instant()) != 1) {
            throw new IllegalStateException("Test result checkpoint was not committed");
        }
        var task = tasks.find(child.taskId());
        if (!task.stopRequested() && !authorized(task)) tasks.haltIdle(task.id(), "AUTHORIZATION_ENDED");
        execution.resolveWaitingTool(child.taskId(), child.toolId(), payload);
    }

    @Transactional
    public List<CopilotTestResponse> recent(String session, String taskId) {
        views.find(session, taskId);
        return tests.recent(taskId);
    }

    private CopilotTaskEntity authorized(String id, String worker, CopilotTestInputDTO input) {
        var task = execution.current(id, worker);
        if (task == null || task.stopRequested()) throw new BusinessException(CopilotErrorCode.COPILOT_INVALID_STATE);
        if (!"USER".equals(identity.authorizeMutation(task.sessionHash()).role())) throw new IdentityFailure(IdentityErrorCode.FORBIDDEN);
        var exercise = exercises.find(task.sessionHash(), task.exerciseId());
        if (ended(exercise)) throw new BusinessException(WorkspaceErrorCode.INVALID_STATE);
        if (exercise.revision() == null || !exercise.revision().toString().equals(input.expectedRevision())) {
            throw new BusinessException(WorkspaceErrorCode.REVISION_CONFLICT);
        }
        return task;
    }

    private boolean authorized(CopilotTaskEntity task) {
        var grant = identity.tryAuthorizeMutation(task.sessionHash());
        return grant != null && task.userId().equals(grant.userId()) && "USER".equals(grant.role()) && !grant.passwordChangeRequired()
            && clock.instant().isBefore(grant.expiresAt());
    }

    private long goal(String task) {
        long sequence = tasks.goalSequence(task);
        if (sequence <= 0) throw new IllegalStateException("Test requires a committed user goal");
        return sequence;
    }

    private boolean ended(ExerciseEntity exercise) {
        return exercise == null || !"ACTIVE".equals(exercise.state()) || exercise.deadlineAt() == null || !clock.instant().isBefore(exercise.deadlineAt());
    }
}
