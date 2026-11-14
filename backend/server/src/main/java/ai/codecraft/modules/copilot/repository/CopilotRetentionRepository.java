package ai.codecraft.modules.copilot.repository;

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
        ) retained
        """)
    long bytes(String exercise);

    @Select("SELECT NOT EXISTS (SELECT 1 FROM copilot_task WHERE exercise_id=#{exercise}::uuid AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','STOPPING')) AND NOT EXISTS (SELECT 1 FROM copilot_turn WHERE exercise_id=#{exercise}::uuid AND state IN ('DISPATCHED','TOOLS')) AND NOT EXISTS (SELECT 1 FROM copilot_tool WHERE exercise_id=#{exercise}::uuid AND state IN ('RUNNING','WAITING_APPROVAL','APPLYING')) AND NOT EXISTS(SELECT 1 FROM copilot_proposal WHERE exercise_id=#{exercise}::uuid AND state IN ('PENDING','APPLYING'))")
    boolean quiescent(String exercise);

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
