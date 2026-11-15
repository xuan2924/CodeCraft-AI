package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.DeletionEntity;
import ai.codecraft.modules.workspace.model.dto.ExerciseHistoryResponse;
import ai.codecraft.modules.workspace.model.dto.ExecutionCleanupDTO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeletionRepository {
    @Update("UPDATE workspace_deletion SET last_manual_retry_at = #{now} WHERE exercise_id = #{id}::uuid AND state IN ('PENDING', 'FAILED') AND (last_manual_retry_at IS NULL OR last_manual_retry_at <= #{now}::timestamptz - interval '1 minute')")
    int admitManualRetry(String id, Instant now);
    @Delete("DELETE FROM workspace_operation WHERE exercise_id = #{id}::uuid AND kind = 'RETRY_DELETE' AND state = 'SUCCEEDED' AND created_at <= #{before}")
    void expireManualRetryReceipts(String id, Instant before);
    String EXPIRED_EXERCISES = "SELECT exercise_id FROM workspace_deletion d WHERE d.state = 'SUCCEEDED' AND d.completed_at <= #{before} AND NOT EXISTS (SELECT 1 FROM workspace_operation o WHERE o.exercise_id = d.exercise_id AND o.state IN ('PENDING', 'PENDING_RECONCILIATION'))";
    @Delete("DELETE FROM workspace_test_stop WHERE task_id IN (SELECT id FROM workspace_test_task WHERE exercise_id IN (" + EXPIRED_EXERCISES + "))")
    void expireTestStops(Instant before);
    @Delete("DELETE FROM workspace_test_task WHERE exercise_id IN (" + EXPIRED_EXERCISES + ")")
    void expireTestReceipts(Instant before);
    @Delete("DELETE FROM workspace_command WHERE exercise_id IN (" + EXPIRED_EXERCISES + ")")
    void expireCommandReceipts(Instant before);
    @Delete("DELETE FROM workspace_finalization WHERE exercise_id IN (" + EXPIRED_EXERCISES + ")")
    void expireFinalizationReceipts(Instant before);
    @Delete("DELETE FROM workspace_transition WHERE exercise_id IN (" + EXPIRED_EXERCISES + ")")
    void expireTransitionReceipts(Instant before);
    String COLUMNS = "exercise_id::text, user_id::text, workspace_instance_id::text, state, requested_at, completed_at, attempts, next_attempt_at, first_failure_at, failure_code";
    @Select("SELECT e.id::text AS exercise_id, e.ended_at, e.retain_until, d.state AS deletion_state FROM workspace_exercise e LEFT JOIN workspace_deletion d ON d.exercise_id = e.id WHERE e.user_id = #{user}::uuid AND e.state = 'ENDED' AND (#{after}::uuid IS NULL OR e.id < #{after}::uuid) AND (d.completed_at IS NULL OR d.completed_at > #{before}) ORDER BY e.id DESC LIMIT 50")
    List<ExerciseHistoryResponse> history(@Param("user") String user, @Param("after") String after, @Param("before") Instant before);
    @Select("SELECT " + COLUMNS + " FROM workspace_deletion WHERE exercise_id = #{id}::uuid")
    DeletionEntity find(String id);
    @Insert("INSERT INTO workspace_deletion(exercise_id, user_id, workspace_instance_id, requested_at, next_attempt_at) VALUES (#{exerciseId}::uuid, #{userId}::uuid, #{workspaceInstanceId}::uuid, #{requestedAt}, #{nextAttemptAt}) ON CONFLICT (exercise_id) DO NOTHING")
    void insert(DeletionEntity value);
    @Select("SELECT " + COLUMNS + " FROM workspace_deletion WHERE state <> 'SUCCEEDED' AND next_attempt_at <= #{now} ORDER BY next_attempt_at, exercise_id LIMIT 16")
    List<DeletionEntity> due(Instant now);
    @Update("UPDATE workspace_deletion SET state = 'RUNNING', next_attempt_at = #{lease}, claim_id = #{claim} WHERE exercise_id = #{id}::uuid AND state <> 'SUCCEEDED' AND next_attempt_at <= #{now}")
    int claim(@Param("id") String id, @Param("now") Instant now, @Param("lease") Instant lease, @Param("claim") String claim);
    @Update("UPDATE workspace_deletion SET state = 'FAILED', attempts = attempts + 1, first_failure_at = coalesce(first_failure_at, #{now}), next_attempt_at = #{next}, failure_code = #{code}, claim_id = NULL WHERE exercise_id = #{id}::uuid AND claim_id = #{claim}")
    void failed(@Param("id") String id, @Param("claim") String claim, @Param("now") Instant now, @Param("next") Instant next, @Param("code") String code);
    @Update("UPDATE workspace_deletion SET state = 'SUCCEEDED', completed_at = #{now}, next_attempt_at = #{now}, attempts = 0, first_failure_at = NULL, failure_code = NULL, claim_id = NULL WHERE exercise_id = #{id}::uuid AND claim_id = #{claim}")
    int complete(@Param("id") String id, @Param("claim") String claim, @Param("now") Instant now);
    @Update("UPDATE workspace_deletion SET next_attempt_at = #{now} WHERE exercise_id = #{id}::uuid AND state IN ('PENDING', 'FAILED')")
    void retry(@Param("id") String id, @Param("now") Instant now);
    @Select("SELECT exercise_id::text FROM workspace_control WHERE exercise_id = #{id}::uuid FOR UPDATE")
    String lockControl(String id);
    @Select("SELECT workspace_instance_id::text FROM workspace_exercise WHERE id = #{id}::uuid UNION SELECT workspace_instance_id::text FROM workspace_operation WHERE exercise_id = #{id}::uuid")
    List<String> workspaceInstances(String id);
    @Select("SELECT count(*) FROM workspace_operation WHERE exercise_id = #{id}::uuid AND state IN ('PENDING', 'PENDING_RECONCILIATION')")
    int unresolved(String id);
    @Select("SELECT quiescent, snapshot->>'cleanup' AS cleanup, snapshot->>'copyCleanup' AS copy_cleanup FROM workspace_test_task WHERE exercise_id = #{id}::uuid UNION ALL SELECT quiescent, snapshot->>'cleanup', snapshot->>'copyCleanup' FROM workspace_command WHERE exercise_id = #{id}::uuid AND NOT pruned")
    List<ExecutionCleanupDTO> cleanupStates(String id);
    @Delete("DELETE FROM workspace_mutation m USING workspace_operation o, workspace_deletion d WHERE m.operation_id = o.id AND o.exercise_id = d.exercise_id AND d.completed_at <= #{before} AND d.state = 'SUCCEEDED' AND o.state NOT IN ('PENDING', 'PENDING_RECONCILIATION')")
    void expireMutationReceipts(Instant before);
    @Delete("DELETE FROM workspace_operation o USING workspace_deletion d WHERE o.exercise_id = d.exercise_id AND d.completed_at <= #{before} AND d.state = 'SUCCEEDED' AND o.kind <> 'PREPARE' AND o.state NOT IN ('PENDING', 'PENDING_RECONCILIATION')")
    void expireReceipts(Instant before);
    @Select("SELECT retain_until FROM workspace_exercise WHERE id = #{id}::uuid")
    Instant retainUntil(String id);
    @Update("UPDATE workspace_mutation SET payload = '{}' WHERE operation_id IN (SELECT id FROM workspace_operation WHERE exercise_id = #{id}::uuid)")
    void eraseMutationPayloads(String id);
    @Delete("DELETE FROM workspace_task_event WHERE task_id IN (SELECT id FROM workspace_test_task WHERE exercise_id = #{id}::uuid)")
    void clearEvents(String id);
    @Delete("DELETE FROM workspace_task_stream WHERE task_id IN (SELECT id FROM workspace_test_task WHERE exercise_id = #{id}::uuid)")
    void clearStreams(String id);
    @Update("UPDATE workspace_test_task SET snapshot = jsonb_set(snapshot, '{logs}', '[]') WHERE exercise_id = #{id}::uuid")
    void eraseTestBodies(String id);
    @Update("UPDATE workspace_command SET command_text='[removed]',working_directory='.',session_hash=repeat('0',64),snapshot=NULL,pruned=true WHERE exercise_id=#{id}::uuid")
    void eraseCommandBodies(String id);
    @Delete("DELETE FROM workspace_final_task WHERE task_id IN (SELECT id FROM workspace_test_task WHERE exercise_id = #{id}::uuid)")
    void clearFinalTasks(String id);
    @Update("UPDATE workspace_transition SET target_hash = NULL, phase = 'SUCCEEDED' WHERE exercise_id = #{id}::uuid")
    void eraseTransitionCredentials(String id);
    @Delete("DELETE FROM workspace_control WHERE exercise_id = #{id}::uuid")
    void clearControl(String id);
    @Update("UPDATE workspace_exercise SET revision = NULL, paths = '[]', preparation_session_hash = NULL, preparation_not_after = NULL, selected_connection_id = NULL, selected_model_id = NULL, selected_connection_version = NULL, selected_model_version = NULL, cleanup_state = 'SUCCEEDED' WHERE id = #{id}::uuid")
    void clearExercise(String id);
    @Select("SELECT e.id::text FROM workspace_exercise e WHERE state = 'ENDED' AND retain_until <= #{now} AND NOT EXISTS (SELECT 1 FROM workspace_deletion d WHERE d.exercise_id = e.id) ORDER BY retain_until, e.id LIMIT 16")
    List<String> expired(Instant now);
}
