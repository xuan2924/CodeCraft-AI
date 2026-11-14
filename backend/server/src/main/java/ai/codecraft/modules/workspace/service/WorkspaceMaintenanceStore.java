package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.identity.service.IdentityTransactionService;
import ai.codecraft.modules.workspace.model.dto.WorkspaceCollectionPlan;
import ai.codecraft.modules.workspace.model.dto.TestHistoryRecord;
import ai.codecraft.modules.workspace.config.StorageProperties;
import ai.codecraft.modules.workspace.repository.DeletionRepository;
import ai.codecraft.modules.workspace.repository.ExerciseRepository;
import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import ai.codecraft.modules.workspace.repository.WorkspaceMaintenanceRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceMaintenanceStore {
    private static final Set<String> COMPLETED = Set.of("PASSED", "FAILED", "TIMED_OUT", "STOPPED", "ENVIRONMENT_ERROR", "INTERRUPTED");
    private final WorkspaceMaintenanceRepository maintenance;
    private final ExerciseRepository exercises;
    private final DeletionRepository deletions;
    private final IdentityTransactionService identity;
    private final Clock clock;
    private final StorageProperties limits;
    private final ExerciseDataService data;

    public WorkspaceMaintenanceStore(WorkspaceMaintenanceRepository maintenance, ExerciseRepository exercises,
        DeletionRepository deletions, IdentityTransactionService identity, Clock clock, StorageProperties limits, ExerciseDataService data) {
        this.maintenance = maintenance; this.exercises = exercises; this.deletions = deletions;
        this.identity = identity; this.clock = clock; this.limits = limits; this.data = data;
    }

    @Transactional(readOnly = true)
    public List<String> candidates() { return maintenance.candidates(); }

    @Transactional
    public List<TestHistoryRecord> pruningPlan(String id) {
        if (lockEligible(id) == null) return List.of();
        var selected = new ArrayList<TestHistoryRecord>();
        var newlyRetired = new ArrayList<String>();
        int completed = 0;
        var protectedRecords = data.protectedTestRecords(id);
        for (var record : maintenance.historyInventory(id)) {
            if (!completedOrdinary(record) || protectedRecords.contains(record.taskId())) continue;
            if (record.pruning()) selected.add(record);
            else if (completed++ >= limits.completedTests()) {
                selected.add(record);
                newlyRetired.add(record.taskId());
            }
        }
        for (int offset = 0; offset < newlyRetired.size(); offset += 1000) {
            var ids = newlyRetired.subList(offset, Math.min(offset + 1000, newlyRetired.size()));
            if (maintenance.markPruningBatch(ids) != ids.size()) throw new IllegalStateException("History selection changed under coordination lock");
        }
        var batch = selected.stream().sorted(Comparator.comparing(TestHistoryRecord::cleanupAttemptedAt,
            Comparator.nullsFirst(Comparator.naturalOrder()))).limit(16).toList();
        return batch;
    }

    @Transactional
    public void pruningAttempted(String taskId) { maintenance.pruningAttempted(taskId, clock.instant()); }

    @Transactional
    public void pruned(String exerciseId, String taskId) {
        if (lockEligible(exerciseId) == null) return;
        var selected = maintenance.historyInventory(exerciseId).stream()
            .filter(value -> value.taskId().equals(taskId) && value.pruning() && completedOrdinary(value)).findFirst();
        if (selected.isEmpty() || maintenance.finishPruning(taskId, clock.instant()) != 1) return;
        maintenance.clearTaskEvents(taskId);
        maintenance.clearTaskStream(taskId);
    }

    private boolean completedOrdinary(TestHistoryRecord value) {
        return !value.finalAttempt() && !value.recoveryRequired() && value.finishedAt() != null && value.quiescent()
            && COMPLETED.contains(value.state())
            && "SUCCEEDED".equals(value.cleanup()) && ("SUCCEEDED".equals(value.copyCleanup()) || "NOT_CREATED".equals(value.copyCleanup()));
    }

    @Transactional
    public void attempted(String id) { maintenance.checked(id, clock.instant()); }

    @Transactional
    public WorkspaceCollectionPlan plan(String id) {
        var exercise = lockEligible(id);
        if (exercise == null) return null;
        maintenance.eraseCompletedRequestBodies(id);
        var references = maintenance.referencedRevisions(id);
        if (references.stream().anyMatch(value -> value == null || value < 0 || value > exercise.revision())) {
            throw new IllegalStateException("Invalid persisted version reference");
        }
        return new WorkspaceCollectionPlan(exercise.workspaceInstanceId(), exercise.revision(), List.copyOf(references));
    }

    private ExerciseEntity lockEligible(String id) {
        var initial = exercises.find(id);
        if (initial == null) return null;
        identity.lockResourceOwner(initial.userId());
        deletions.lockControl(id);
        var exercise = exercises.find(id);
        return exercise == null || exercise.revision() == null || exercises.deleted(id)
            || !List.of("READY", "ACTIVE", "CLOSING", "FINAL_TESTING", "ENDED").contains(exercise.state()) ? null : exercise;
    }
}
