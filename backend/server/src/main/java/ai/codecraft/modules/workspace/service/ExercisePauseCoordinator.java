package ai.codecraft.modules.workspace.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Restartable pause outbox: side effects settle before the clock is suspended. */
@Service
public class ExercisePauseCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(ExercisePauseCoordinator.class);
    private final ExercisePauseStore store;
    private final WorkspaceCoordinationStore workspace;
    private final WorkspaceWriteCoordinator fences;
    private final TestTaskCoordinator tests;
    private final CommandTaskStore commands;
    private final CommandTaskCoordinator commandCoordinator;
    private final FinalizationStore finals;
    private final ai.codecraft.modules.workspace.stream.TerminalConnections terminals;

    public ExercisePauseCoordinator(ExercisePauseStore store, WorkspaceCoordinationStore workspace, WorkspaceWriteCoordinator fences,
        TestTaskCoordinator tests, CommandTaskStore commands, CommandTaskCoordinator commandCoordinator, FinalizationStore finals, ai.codecraft.modules.workspace.stream.TerminalConnections terminals) {
        this.store = store; this.workspace = workspace; this.fences = fences; this.tests = tests;
        this.commands = commands; this.commandCoordinator = commandCoordinator; this.finals = finals; this.terminals = terminals;
    }

    @Scheduled(fixedDelay = 1000)
    public void recover() {
        for (String exercise : store.pending()) tryDrive(exercise);
    }

    public void tryDrive(String exercise) {
        try { drive(exercise); }
        catch (RuntimeException error) { LOG.warn("Exercise {} pause remains unresolved ({})", exercise, error.getClass().getSimpleName()); }
    }

    public void drive(String id) {
        store.attempted(id);
        var exercise = workspace.exercise(id);
        var pause = store.find(id);
        if (exercise == null || pause == null) return;
        if ("PAUSING".equals(exercise.state())) {
            terminals.closeExercise(id);
            // Expiry wins even when Go is unavailable and the close receipt remains unknown.
            finals.expire(id);
            if (!"PAUSING".equals(workspace.exercise(id).state())) { store.superseded(id); return; }
            fences.drive(pause.pauseOperationId());
            for (var task : store.tests(id)) tests.stopSystem(task);
            for (var task : store.commands(id)) {
                commands.requestSystemStop(task.id());
                commandCoordinator.observe(commands.find(task.id()));
            }
            if (!store.confirmPause(id)) finals.expire(id);
        } else if ("RESUMING".equals(exercise.state())) {
            fences.drive(pause.resumeOperationId());
            store.confirmResume(id);
        } else store.superseded(id);
    }
}
