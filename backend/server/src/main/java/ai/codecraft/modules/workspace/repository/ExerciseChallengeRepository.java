package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.ExerciseChallengeEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExerciseChallengeRepository {
    @Select("SELECT exercise_id::text,operation_id::text,entered_revision,base_test_id::text,entered_at FROM workspace_challenge WHERE exercise_id=#{id}::uuid")
    ExerciseChallengeEntity find(String id);
    @Select("SELECT t.id::text FROM workspace_test_task t JOIN workspace_exercise e ON e.id=t.exercise_id WHERE e.id=#{exercise}::uuid AND t.workspace_instance_id=e.workspace_instance_id AND t.revision=e.revision AND t.target='base' AND t.state='PASSED' AND t.quiescent AND NOT t.history_pruning AND t.history_pruned_at IS NULL AND t.snapshot->>'state'='PASSED' AND t.snapshot->'evidence'->>'kind'='SUREFIRE_XML' ORDER BY t.created_at DESC,t.id DESC LIMIT 1")
    String currentBasePass(String exercise);
    @Insert("INSERT INTO workspace_challenge(exercise_id,operation_id,entered_revision,base_test_id,entered_at) VALUES(#{exercise}::uuid,#{operation}::uuid,#{revision},#{test}::uuid,#{now})")
    void enter(String exercise, String operation, long revision, String test, Instant now);
    @Update("UPDATE workspace_exercise SET state_version=state_version+1 WHERE id=#{id}::uuid")
    void changed(String id);
    @Select("SELECT coalesce(sum(pg_column_size(c)),0)::bigint FROM workspace_challenge c WHERE exercise_id=#{id}::uuid")
    long bytes(String id);
    @Delete("DELETE FROM workspace_challenge WHERE exercise_id=#{id}::uuid")
    void erase(String id);
}
