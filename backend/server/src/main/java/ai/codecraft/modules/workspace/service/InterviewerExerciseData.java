package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.repository.InterviewerRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class InterviewerExerciseData implements ExerciseDataContributor {
    private final InterviewerRepository tasks;
    private final ExerciseRepository exercises;
    private final Clock clock;
    public InterviewerExerciseData(InterviewerRepository tasks, ExerciseRepository exercises, Clock clock) {
        this.tasks = tasks; this.exercises = exercises; this.clock = clock;
    }
    @Override public long retainedBytes(String exercise) { return tasks.bytes(exercise); }
    @Override public boolean quiescent(String exercise) { return !tasks.active(exercise); }
    @Override public void erase(String exercise) { tasks.erase(exercise); }
    @Override public void pause(String exercise) {
        for (String id : tasks.pause(exercise, clock.instant())) exercises.operationState(id, "REJECTED");
    }
}
