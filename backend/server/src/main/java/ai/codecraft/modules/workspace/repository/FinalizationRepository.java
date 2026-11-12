package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.FinalizationEntity;
import ai.codecraft.modules.workspace.model.entity.FinalTaskAuthorityEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FinalizationRepository {
    String COLUMNS = "id::text, exercise_id::text, frozen_epoch, final_revision, started_at, deadline_at, max_attempts, budget_seconds, first_retry_seconds, second_retry_seconds, attempts, current_task_id::text, next_attempt_at, verification, result_task_id::text, ended_at, cancellation_pending";

    @Select("SELECT " + COLUMNS + " FROM workspace_finalization WHERE exercise_id = #{exercise}::uuid")
    FinalizationEntity find(String exercise);
    @Select("SELECT " + COLUMNS + " FROM workspace_finalization WHERE ended_at IS NULL OR cancellation_pending ORDER BY last_attempt_at, id LIMIT 16")
    List<FinalizationEntity> pending();
    @Select("SELECT id::text FROM workspace_exercise WHERE state = 'ACTIVE' AND deadline_at <= #{now} ORDER BY deadline_at, id LIMIT 16")
    List<String> expired(Instant now);
    @Insert("INSERT INTO workspace_finalization(id, exercise_id, max_attempts, budget_seconds, first_retry_seconds, second_retry_seconds) VALUES (#{id}::uuid, #{exerciseId}::uuid, #{maxAttempts}, #{budgetSeconds}, #{firstRetrySeconds}, #{secondRetrySeconds})")
    void insert(FinalizationEntity value);
    @Update("UPDATE workspace_finalization SET last_attempt_at = #{now} WHERE exercise_id = #{exercise}::uuid")
    void attempted(@Param("exercise") String exercise, @Param("now") Instant now);
    @Update("UPDATE workspace_exercise SET state = 'CLOSING', end_reason = #{reason}, state_version = state_version + 1 WHERE id = #{exercise}::uuid AND state = 'ACTIVE'")
    int closing(@Param("exercise") String exercise, @Param("reason") String reason);
    @Update("UPDATE workspace_finalization SET frozen_epoch = #{epoch}, final_revision = #{revision}, started_at = #{now}, deadline_at = #{deadline} WHERE exercise_id = #{exercise}::uuid AND frozen_epoch IS NULL")
    int frozen(@Param("exercise") String exercise, @Param("epoch") long epoch, @Param("revision") long revision, @Param("now") Instant now, @Param("deadline") Instant deadline);
    @Update("UPDATE workspace_exercise SET state = 'FINAL_TESTING', revision = #{revision}, state_version = state_version + 1 WHERE id = #{exercise}::uuid AND state = 'CLOSING'")
    void testing(@Param("exercise") String exercise, @Param("revision") long revision);
    @Update("UPDATE workspace_finalization SET ended_at = #{now}, verification = #{result}, result_task_id = #{task}::uuid, cancellation_pending = true WHERE exercise_id = #{exercise}::uuid AND ended_at IS NULL")
    void finish(@Param("exercise") String exercise, @Param("now") Instant now, @Param("result") String result, @Param("task") String task);
    @Update("UPDATE workspace_exercise SET state = 'ENDED', state_version = state_version + 1 WHERE id = #{exercise}::uuid AND state = 'FINAL_TESTING'")
    void ended(String exercise);
    @Update("UPDATE workspace_finalization SET attempts = attempts + 1, current_task_id = #{task}::uuid, next_attempt_at = NULL WHERE exercise_id = #{exercise}::uuid AND ended_at IS NULL AND attempts < max_attempts")
    int allocate(@Param("exercise") String exercise, @Param("task") String task);
    @Update("UPDATE workspace_finalization SET current_task_id = NULL, next_attempt_at = #{next} WHERE exercise_id = #{exercise}::uuid AND current_task_id = #{task}::uuid AND ended_at IS NULL")
    void retry(@Param("exercise") String exercise, @Param("task") String task, @Param("next") Instant next);
    @Update("UPDATE workspace_finalization SET cancellation_pending = false WHERE exercise_id = #{exercise}::uuid AND ended_at IS NOT NULL")
    void cancelled(String exercise);
    @Insert("INSERT INTO workspace_final_task(task_id, finalization_id, freeze_operation_id, deadline_at) VALUES (#{taskId}::uuid, #{finalizationId}::uuid, #{freezeOperationId}::uuid, #{deadlineAt})")
    void bind(FinalTaskAuthorityEntity authority);
    @Select("SELECT task_id::text, finalization_id::text, freeze_operation_id::text, deadline_at FROM workspace_final_task WHERE task_id = #{id}::uuid")
    FinalTaskAuthorityEntity authority(String id);
    @Select("SELECT " + TestTaskRepository.COLUMNS + " FROM workspace_test_task WHERE exercise_id = #{exercise}::uuid AND NOT quiescent ORDER BY id LIMIT 16")
    List<TestTaskEntity> outstanding(String exercise);
    @Select("SELECT " + TestTaskRepository.COLUMNS + " FROM workspace_test_task WHERE exercise_id = #{exercise}::uuid AND workspace_instance_id = #{instance}::uuid AND revision = #{revision} AND target = 'base' AND state IN ('PASSED', 'FAILED', 'TIMED_OUT') AND snapshot IS NOT NULL ORDER BY created_at DESC, id DESC LIMIT 1")
    TestTaskEntity reusable(@Param("exercise") String exercise, @Param("instance") String instance, @Param("revision") long revision);
}
