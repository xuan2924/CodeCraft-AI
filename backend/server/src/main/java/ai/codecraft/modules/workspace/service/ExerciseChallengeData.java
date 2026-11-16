package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.repository.ExerciseChallengeRepository;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ExerciseChallengeData implements ExerciseDataContributor {
    private final ExerciseChallengeRepository challenges;
    public ExerciseChallengeData(ExerciseChallengeRepository challenges) { this.challenges = challenges; }
    @Override public long retainedBytes(String exercise) { return challenges.bytes(exercise); }
    @Override public boolean quiescent(String exercise) { return true; }
    @Override public void erase(String exercise) { challenges.erase(exercise); }
    @Override public Set<String> protectedTestRecords(String exercise) {
        var entered = challenges.find(exercise);
        return entered == null ? Set.of() : Set.of(entered.baseTestId());
    }
}
