package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.repository.ExercisePauseRepository;
import org.springframework.stereotype.Service;

/** Pause metadata shares the exercise's capacity, retention and deletion boundary. */
@Service
public class ExercisePauseData implements ExerciseDataContributor {
    private final ExercisePauseRepository repository;
    public ExercisePauseData(ExercisePauseRepository repository) { this.repository = repository; }
    @Override public long retainedBytes(String exerciseId) { return repository.bytes(exerciseId); }
    // Operation receipts and gate transitions are checked by workspace orchestration, not this metadata row.
    @Override public boolean quiescent(String exerciseId) { return true; }
    @Override public void erase(String exerciseId) { repository.delete(exerciseId); }
}
