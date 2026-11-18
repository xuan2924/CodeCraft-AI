package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.InterviewerDispatchDTO;
import ai.codecraft.modules.workspace.repository.InterviewerRepository;
import java.util.ArrayList;
import java.util.Collections;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, untrusted conversation and saved-code evidence; remote reads never hold a transaction. */
@Service
public class InterviewerContextCollector {
    private final InterviewerRepository tasks;
    private final ExerciseService exercises;
    private final WorkspaceHistoryService history;
    public InterviewerContextCollector(InterviewerRepository tasks, ExerciseService exercises, WorkspaceHistoryService history) {
        this.tasks = tasks; this.exercises = exercises; this.history = history;
    }
    @Transactional(propagation = Propagation.NEVER)
    public String collect(InterviewerDispatchDTO dispatch) {
        StringBuilder context = new StringBuilder("Current stage: " + dispatch.stage() + "; revision: " + dispatch.revision()
            + "; mode: " + dispatch.mode() + "; topic: " + dispatch.topic() + "; hint level: " + dispatch.hintLevel()
            + "\nLatest controlled test: " + tasks.latestTest(dispatch.exerciseId()) + "\nUntrusted completed history (oldest first):\n");
        var completed = new ArrayList<>(tasks.completedHistory(dispatch.exerciseId()).stream().limit(8).toList());
        Collections.reverse(completed);
        for (var turn : completed) {
            context.append("Question: ").append(excerpt(turn.question(), 1024))
                .append("\nAnswer: ").append(excerpt(turn.answer(), 2048)).append('\n');
        }
        if ("DEFENSE".equals(dispatch.mode())) {
            var snapshot = exercises.find(dispatch.sessionHash(), dispatch.exerciseId());
            if (!snapshot.revision().equals(String.valueOf(dispatch.revision()))) throw new IllegalStateException("Defense revision changed");
            context.append("\nUntrusted saved editable source excerpts:\n");
            var language = ProblemLanguage.valueOf(tasks.problemLanguage(snapshot.problemVersionId()));
            var changes = history.recent(dispatch.sessionHash(), dispatch.exerciseId(), Long.MAX_VALUE).changes().stream().limit(8).toList();
            var paths = new java.util.LinkedHashSet<String>();
            for (var change : changes) {
                if (!change.retained() || Long.parseLong(change.resultRevision()) > dispatch.revision()) continue;
                var preview = history.preview(dispatch.sessionHash(), dispatch.exerciseId(), change.id(), snapshot.revision(), "DIFF", null);
                preview.changes().stream().map(file -> file.path()).filter(snapshot.paths()::contains).filter(language::editable).forEach(paths::add);
                if (paths.size() >= 4) break;
            }
            snapshot.paths().stream().filter(language::editable).sorted().forEach(paths::add);
            for (String path : paths.stream().limit(4).toList()) {
                var file = exercises.read(dispatch.sessionHash(), dispatch.exerciseId(), path);
                if (!file.revision().equals(snapshot.revision())) throw new IllegalStateException("Defense revision changed");
                context.append(path).append('\n').append(excerpt(file.content(), 2048)).append('\n');
            }
            context.append("\nRecent saved changes (at or before evidence revision):\n");
            for (var change : changes) {
                if (Long.parseLong(change.resultRevision()) <= dispatch.revision()) {
                    context.append(change.source()).append(" r").append(change.baseRevision()).append(" -> r")
                        .append(change.resultRevision()).append('\n');
                }
            }
        }
        return context.toString();
    }
    static String excerpt(String text, int limit) {
        if (text == null) return "";
        int end = text.offsetByCodePoints(0, Math.min(limit, text.codePointCount(0, text.length())));
        return text.substring(0, end) + (end < text.length() ? " [truncated]" : "");
    }
}
