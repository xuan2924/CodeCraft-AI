package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.model.dto.WorkspaceChangeContext;
import java.util.Set;

/** Domain-owned evidence participating in the exercise's single capacity and deletion boundary.
 * Calls run inside the workspace owner's transaction/lock; implementations must not perform external IO.
 */
public interface ExerciseDataContributor {
    long retainedBytes(String exerciseId);
    boolean quiescent(String exerciseId);
    void erase(String exerciseId);
    default Set<String> protectedTestRecords(String exerciseId) { return Set.of(); }
    default Set<String> protectedCommandRecords(String exerciseId) { return Set.of(); }
    default WorkspaceChangeContext changeContext(String exerciseId, String operationId) { return null; }
}
