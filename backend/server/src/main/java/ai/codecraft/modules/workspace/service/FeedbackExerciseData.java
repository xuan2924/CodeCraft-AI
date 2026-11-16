package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.repository.FeedbackRepository;
import org.springframework.stereotype.Service;

@Service
public class FeedbackExerciseData implements ExerciseDataContributor {
    private final FeedbackRepository feedback;
    public FeedbackExerciseData(FeedbackRepository feedback) { this.feedback = feedback; }
    @Override public long retainedBytes(String exercise) { return feedback.bytes(exercise); }
    @Override public boolean quiescent(String exercise) { return true; }
    @Override public void erase(String exercise) { feedback.erase(exercise); }
}
