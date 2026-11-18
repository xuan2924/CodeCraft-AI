package ai.codecraft.modules.workspace.service;

import ai.codecraft.infrastructure.tasks.OutboxEvent;
import ai.codecraft.infrastructure.tasks.OutboxHandler;
import ai.codecraft.modules.workspace.repository.ExerciseReportRepository;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceOutboxHandler implements OutboxHandler {
    private final TestTaskStore tests;
    private final TestTaskCoordinator testWorker;
    private final CommandTaskStore commands;
    private final CommandTaskCoordinator commandWorker;
    private final ExerciseReportRepository reports;
    private final EvaluationReportWorker evaluator;
    public WorkspaceOutboxHandler(TestTaskStore tests, TestTaskCoordinator testWorker, CommandTaskStore commands,
        CommandTaskCoordinator commandWorker, ExerciseReportRepository reports, EvaluationReportWorker evaluator) {
        this.tests = tests; this.testWorker = testWorker; this.commands = commands; this.commandWorker = commandWorker;
        this.reports = reports; this.evaluator = evaluator;
    }
    @Override public void handle(OutboxEvent event) {
        switch (event.kind()) {
            case "TEST" -> {
                var task = tests.find(event.aggregateId());
                if (task != null && !task.quiescent()) {
                    if (!task.exerciseId().equals(event.exerciseId())) throw new IllegalStateException("Outbox owner mismatch");
                    testWorker.observe(task);
                    var current = tests.find(task.id());
                    if (current != null && !current.dispatched() && !current.quiescent()) throw new IllegalStateException("Test admission deferred");
                }
            }
            case "COMMAND" -> {
                var task = commands.find(event.aggregateId());
                if (task != null && !task.quiescent() && !task.pruned()) {
                    if (!task.exerciseId().equals(event.exerciseId())) throw new IllegalStateException("Outbox owner mismatch");
                    commandWorker.observe(task);
                    var current = commands.find(task.id());
                    if (current != null && !current.dispatched() && !current.quiescent()) throw new IllegalStateException("Command admission deferred");
                }
            }
            case "REPORT" -> {
                if (!event.aggregateId().equals(event.exerciseId())) throw new IllegalStateException("Report outbox owner mismatch");
                var report = reports.find(event.aggregateId());
                if (report == null || report.generation() != event.generation() || report.attempts() != event.attempt()) return;
                evaluator.run(event.aggregateId(), event.generation(), event.attempt());
                report = reports.find(event.aggregateId());
                if (report != null && report.generation() == event.generation() && report.attempts() == event.attempt()
                    && java.util.Set.of("QUEUED", "PREPARING").contains(report.state())) throw new IllegalStateException("Evaluation preparation unresolved");
            }
            default -> throw new IllegalArgumentException("Unknown outbox task kind");
        }
    }
}
