package ai.codecraft.modules.workspace.service;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
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

    public Set<String> protectedTestRecords(String exerciseId) {
        var protectedRecords = new HashSet<String>();
        for (var contributor : contributors) protectedRecords.addAll(contributor.protectedTestRecords(exerciseId));
        return Set.copyOf(protectedRecords);
    }

    public Set<String> protectedCommandRecords(String exerciseId) {
        var protectedRecords = new HashSet<String>();
        for (var contributor : contributors) protectedRecords.addAll(contributor.protectedCommandRecords(exerciseId));
        return Set.copyOf(protectedRecords);
    }

    public boolean quiescent(String exerciseId) {
        return contributors.stream().allMatch(contributor -> contributor.quiescent(exerciseId));
    }

    public void erase(String exerciseId) {
        if (!quiescent(exerciseId)) throw new IllegalStateException("Exercise data still has unresolved operations");
        for (var contributor : contributors) contributor.erase(exerciseId);
    }
}
