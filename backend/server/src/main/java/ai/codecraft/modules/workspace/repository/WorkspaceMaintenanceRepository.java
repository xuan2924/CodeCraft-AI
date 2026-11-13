package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.dto.TestHistoryRecord;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkspaceMaintenanceRepository {
    @Select("SELECT e.id::text FROM workspace_exercise e WHERE e.revision IS NOT NULL AND e.state IN ('READY', 'ACTIVE', 'CLOSING', 'FINAL_TESTING', 'ENDED') AND NOT EXISTS (SELECT 1 FROM workspace_deletion d WHERE d.exercise_id = e.id) ORDER BY e.maintenance_checked_at, e.id LIMIT 16")
    List<String> candidates();

    @Select("SELECT revision FROM workspace_test_task WHERE exercise_id = #{id}::uuid AND history_pruned_at IS NULL UNION SELECT final_revision FROM workspace_finalization WHERE exercise_id = #{id}::uuid AND final_revision IS NOT NULL UNION SELECT (m.payload::jsonb->>'expectedRevision')::bigint FROM workspace_mutation m JOIN workspace_operation o ON o.id = m.operation_id WHERE o.exercise_id = #{id}::uuid AND (o.state IN ('PENDING', 'PENDING_RECONCILIATION') OR (o.state = 'REJECTED' AND m.failure_code = 'REVISION_CONFLICT')) AND m.payload::jsonb->>'expectedRevision' IS NOT NULL")
    List<Long> referencedRevisions(String id);

    @Select("SELECT t.id::text AS task_id, t.execution_instance_id::text, t.workspace_instance_id::text, t.revision, EXISTS (SELECT 1 FROM workspace_final_task f WHERE f.task_id = t.id) AS final_attempt, t.quiescent, t.state, t.snapshot->>'cleanup' AS cleanup, t.snapshot->>'copyCleanup' AS copy_cleanup, t.history_pruning AS pruning, EXISTS (SELECT 1 FROM workspace_test_stop s WHERE s.task_id = t.id AND s.state = 'PENDING') AS recovery_required, (t.snapshot->>'finishedAt')::timestamptz AS finished_at, t.history_cleanup_attempted_at AS cleanup_attempted_at FROM workspace_test_task t WHERE t.exercise_id = #{id}::uuid AND t.history_pruned_at IS NULL ORDER BY (t.snapshot->>'finishedAt')::timestamptz DESC NULLS FIRST, t.id DESC")
    List<TestHistoryRecord> historyInventory(String id);

    @Update("UPDATE workspace_test_task SET history_cleanup_attempted_at = #{now} WHERE id = #{id}::uuid AND history_pruning")
    void pruningAttempted(String id, Instant now);

    @Update("UPDATE workspace_test_task SET history_pruning = true WHERE id = #{id}::uuid AND NOT history_pruning AND history_pruned_at IS NULL AND NOT EXISTS (SELECT 1 FROM workspace_final_task f WHERE f.task_id = workspace_test_task.id)")
    int markPruning(String id);

    @Update("UPDATE workspace_test_task SET history_pruning = false, history_pruned_at = #{now}, snapshot = jsonb_set(jsonb_set(snapshot, '{logs}', '[]'), '{evidence}', 'null') WHERE id = #{id}::uuid AND history_pruning")
    int finishPruning(String id, Instant now);

    @org.apache.ibatis.annotations.Delete("DELETE FROM workspace_task_event WHERE task_id = #{id}::uuid")
    void clearTaskEvents(String id);
    @org.apache.ibatis.annotations.Delete("DELETE FROM workspace_task_stream WHERE task_id = #{id}::uuid")
    void clearTaskStream(String id);

    @Update("UPDATE workspace_mutation m SET payload = jsonb_build_object('expectedRevision', m.payload::jsonb->>'expectedRevision')::text FROM workspace_operation o WHERE m.operation_id = o.id AND o.exercise_id = #{id}::uuid AND o.state NOT IN ('PENDING', 'PENDING_RECONCILIATION')")
    void eraseCompletedRequestBodies(String id);

    @Update("UPDATE workspace_exercise SET maintenance_checked_at = #{now} WHERE id = #{id}::uuid")
    void checked(String id, Instant now);
}
