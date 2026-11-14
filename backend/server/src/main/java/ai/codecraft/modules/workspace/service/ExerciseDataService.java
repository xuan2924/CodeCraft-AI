package ai.codecraft.modules.workspace.service;

import java.util.List;
import org.springframework.stereotype.Service;

/** Keeps capacity/deletion orchestration independent from the storage of attached domains. */
@Service
public class ExerciseDataService {
    private final List<ExerciseDataContributor> contributors;
    public ExerciseDataService(List<ExerciseDataContributor> contributors) { this.contributors = List.copyOf(contributors); }

    public long retainedBytes(String exerciseId) {
        long total = 0;
        for (var contributor : contributors) {
            long bytes = contributor.retainedBytes(exerciseId);
            if (bytes < 0) throw new IllegalStateException("Invalid exercise data measurement");
            total = Math.addExact(total, bytes);
        }
        return total;
    }

    public boolean quiescent(String exerciseId) {
        return contributors.stream().allMatch(contributor -> contributor.quiescent(exerciseId));
    }

    public void erase(String exerciseId) {
        if (!quiescent(exerciseId)) throw new IllegalStateException("Exercise data still has unresolved operations");
        for (var contributor : contributors) contributor.erase(exerciseId);
    }
}
