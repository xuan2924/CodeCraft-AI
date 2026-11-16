package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.ExercisePauseEntity;
import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExercisePauseRepository {
    String COLUMNS = "exercise_id::text,pause_operation_id::text,resume_operation_id::text,remaining_millis,confirmed_at";

    @Select("SELECT coalesce(sum(pg_column_size(p)),0)::bigint FROM workspace_pause p WHERE exercise_id=#{exercise}::uuid")
    long bytes(String exercise);
    @Delete("DELETE FROM workspace_pause WHERE exercise_id=#{exercise}::uuid")
    void delete(String exercise);
    @Select("SELECT " + COLUMNS + " FROM workspace_pause WHERE exercise_id=#{exercise}::uuid")
    ExercisePauseEntity find(String exercise);
    @Select("SELECT p.exercise_id::text FROM workspace_pause p WHERE EXISTS(SELECT 1 FROM workspace_operation o WHERE o.id IN (p.pause_operation_id,p.resume_operation_id) AND o.state IN ('PENDING','PENDING_RECONCILIATION')) ORDER BY p.last_attempt_at,p.exercise_id LIMIT 16")
    List<String> pending();
    @Update("UPDATE workspace_pause SET last_attempt_at=#{now} WHERE exercise_id=#{exercise}::uuid")
    void attempted(String exercise, Instant now);
    @Insert("INSERT INTO workspace_pause(exercise_id,pause_operation_id) VALUES(#{exercise}::uuid,#{operation}::uuid) ON CONFLICT(exercise_id) DO UPDATE SET pause_operation_id=EXCLUDED.pause_operation_id,resume_operation_id=NULL,remaining_millis=NULL,confirmed_at=NULL,last_attempt_at='epoch'")
    void accept(String exercise, String operation);
    @Update("UPDATE workspace_exercise SET state='PAUSING',state_version=state_version+1 WHERE id=#{exercise}::uuid AND state='ACTIVE' AND deadline_at>#{now}")
    int requestPause(String exercise, Instant now);
    @Update("UPDATE workspace_pause SET remaining_millis=#{remaining},confirmed_at=#{now} WHERE exercise_id=#{exercise}::uuid")
    void remaining(String exercise, long remaining, Instant now);
    @Update("UPDATE workspace_exercise SET state='PAUSED',deadline_at=NULL,state_version=state_version+1 WHERE id=#{exercise}::uuid AND state IN ('PAUSING','RESUMING')")
    int paused(String exercise);
    @Update("UPDATE workspace_pause SET resume_operation_id=#{operation}::uuid,last_attempt_at='epoch' WHERE exercise_id=#{exercise}::uuid")
    void resumeOperation(String exercise, String operation);
    @Update("UPDATE workspace_exercise SET state='RESUMING',state_version=state_version+1 WHERE id=#{exercise}::uuid AND state='PAUSED'")
    int requestResume(String exercise);
    @Update("UPDATE workspace_exercise SET state='ACTIVE',deadline_at=#{deadline},state_version=state_version+1 WHERE id=#{exercise}::uuid AND state='RESUMING'")
    int resumed(String exercise, Instant deadline);
    @Select("SELECT " + TestTaskRepository.COLUMNS + " FROM workspace_test_task WHERE exercise_id=#{exercise}::uuid AND NOT quiescent ORDER BY id LIMIT 16")
    List<TestTaskEntity> tests(String exercise);
    @Select("SELECT " + CommandTaskRepository.COLUMNS + " FROM workspace_command WHERE exercise_id=#{exercise}::uuid AND NOT pruned AND (NOT quiescent OR synchronization='PENDING') ORDER BY id LIMIT 16")
    List<CommandTaskEntity> commands(String exercise);
    @Select("SELECT NOT EXISTS(SELECT 1 FROM workspace_test_task WHERE exercise_id=#{exercise}::uuid AND NOT quiescent) AND NOT EXISTS(SELECT 1 FROM workspace_command WHERE exercise_id=#{exercise}::uuid AND NOT pruned AND (NOT quiescent OR synchronization='PENDING'))")
    boolean executionsQuiescent(String exercise);
}
