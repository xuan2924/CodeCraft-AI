package ai.codecraft.modules.copilot.repository;

import java.util.Set;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopilotRetentionRepository {
    @Select("""
        SELECT coalesce(sum(bytes),0)::bigint FROM (
          SELECT sum(pg_column_size(t)) AS bytes FROM copilot_task t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_message t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_turn t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_tool t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_proposal t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_test_run t WHERE exercise_id=#{exercise}::uuid
        ) retained
        """)
    long bytes(String exercise);

    @Select("SELECT NOT EXISTS (SELECT 1 FROM copilot_task WHERE exercise_id=#{exercise}::uuid AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','STOPPING')) AND NOT EXISTS (SELECT 1 FROM copilot_turn WHERE exercise_id=#{exercise}::uuid AND state IN ('DISPATCHED','TOOLS')) AND NOT EXISTS (SELECT 1 FROM copilot_tool WHERE exercise_id=#{exercise}::uuid AND state IN ('RUNNING','WAITING_APPROVAL','APPLYING','WAITING_TEST')) AND NOT EXISTS(SELECT 1 FROM copilot_proposal WHERE exercise_id=#{exercise}::uuid AND state IN ('PENDING','APPLYING')) AND NOT EXISTS(SELECT 1 FROM copilot_test_run WHERE exercise_id=#{exercise}::uuid AND state='WAITING')")
    boolean quiescent(String exercise);

    @Select("SELECT test_task_id::text FROM copilot_test_run WHERE exercise_id=#{exercise}::uuid AND state='WAITING'")
    Set<String> protectedTestRecords(String exercise);
    @Delete("DELETE FROM copilot_test_run WHERE exercise_id=#{exercise}::uuid")
    void deleteTests(String exercise);
    @Delete("DELETE FROM copilot_proposal WHERE exercise_id=#{exercise}::uuid")
    void deleteProposals(String exercise);
    @Delete("DELETE FROM copilot_tool WHERE exercise_id=#{exercise}::uuid")
    void deleteTools(String exercise);
    @Delete("DELETE FROM copilot_message WHERE exercise_id=#{exercise}::uuid")
    void deleteMessages(String exercise);
    @Delete("DELETE FROM copilot_turn WHERE exercise_id=#{exercise}::uuid")
    void deleteTurns(String exercise);
    @Delete("DELETE FROM copilot_task WHERE exercise_id=#{exercise}::uuid")
    void deleteTasks(String exercise);
}
