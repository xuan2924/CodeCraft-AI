package ai.codecraft.modules.skills.service;

import ai.codecraft.modules.skills.repository.SkillSnapshotRepository;
import ai.codecraft.modules.workspace.service.ExerciseDataContributor;
import org.springframework.stereotype.Service;

/** Shared versions count once per owning exercise, not once globally. Called under the owner lock.
 * Copilot's contributor separately prevents deletion while task/tool evidence is unresolved. */
@Service
public class SkillExerciseData implements ExerciseDataContributor {
    private final SkillSnapshotRepository repository;
    public SkillExerciseData(SkillSnapshotRepository repository) { this.repository = repository; }
    @Override public long retainedBytes(String exerciseId) { return repository.retainedBytes(exerciseId); }
    @Override public boolean quiescent(String exerciseId) { return true; }
    @Override public void erase(String exerciseId) { repository.erase(exerciseId); }
}
