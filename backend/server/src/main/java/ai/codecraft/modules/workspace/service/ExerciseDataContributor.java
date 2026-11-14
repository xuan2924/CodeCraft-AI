package ai.codecraft.modules.workspace.service;

/** Domain-owned evidence participating in the exercise's single capacity and deletion boundary.
 * Calls run inside the workspace owner's transaction/lock; implementations must not perform external IO.
 */
public interface ExerciseDataContributor {
    long retainedBytes(String exerciseId);
    boolean quiescent(String exerciseId);
    void erase(String exerciseId);
}
