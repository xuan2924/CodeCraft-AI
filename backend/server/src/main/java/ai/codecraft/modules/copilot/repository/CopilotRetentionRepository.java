package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.workspace.model.dto.WorkspaceChangeContext;
import java.util.Set;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopilotRetentionRepository {
    @Select("SELECT t.id::text AS task_id,coalesce(parent.conversation_id,t.conversation_id)::text AS conversation_id FROM copilot_proposal p JOIN copilot_task t ON t.id=p.task_id LEFT JOIN copilot_delegation d ON d.child_task_id=t.id LEFT JOIN copilot_task parent ON parent.id=d.parent_task_id WHERE p.operation_id=#{operation}::uuid AND p.exercise_id=#{exercise}::uuid AND t.exercise_id=#{exercise}::uuid UNION SELECT t.id::text,coalesce(parent.conversation_id,t.conversation_id)::text FROM copilot_command c JOIN copilot_task t ON t.id=c.task_id LEFT JOIN copilot_delegation d ON d.child_task_id=t.id LEFT JOIN copilot_task parent ON parent.id=d.parent_task_id WHERE c.command_task_id=#{operation}::uuid AND c.exercise_id=#{exercise}::uuid AND t.exercise_id=#{exercise}::uuid")
    WorkspaceChangeContext changeContext(@Param("exercise") String exercise, @Param("operation") String operation);

    @Select("""
        SELECT coalesce(sum(bytes),0)::bigint FROM (
          SELECT sum(pg_column_size(t)) AS bytes FROM copilot_task t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_conversation t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_fork_message t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_btw t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_followup t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_followup_resume t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_message t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_turn t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_tool t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_proposal t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_test_run t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_command t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_approval_mode t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_delegation t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_approval_change t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_steer t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_context t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_compaction t WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM copilot_compaction_call t WHERE exercise_id=#{exercise}::uuid
        ) retained
        """)
    long bytes(String exercise);

    @Select("SELECT NOT EXISTS (SELECT 1 FROM copilot_task WHERE exercise_id=#{exercise}::uuid AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING')) AND NOT EXISTS (SELECT 1 FROM copilot_turn WHERE exercise_id=#{exercise}::uuid AND state IN ('DISPATCHED','TOOLS')) AND NOT EXISTS (SELECT 1 FROM copilot_tool WHERE exercise_id=#{exercise}::uuid AND state IN ('RUNNING','WAITING_APPROVAL','APPLYING','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD')) AND NOT EXISTS(SELECT 1 FROM copilot_proposal WHERE exercise_id=#{exercise}::uuid AND state IN ('PENDING','APPLYING')) AND NOT EXISTS(SELECT 1 FROM copilot_test_run WHERE exercise_id=#{exercise}::uuid AND state='WAITING') AND NOT EXISTS(SELECT 1 FROM copilot_command WHERE exercise_id=#{exercise}::uuid AND state IN ('PENDING','WAITING')) AND NOT EXISTS(SELECT 1 FROM copilot_delegation WHERE exercise_id=#{exercise}::uuid AND state='WAITING') AND NOT EXISTS(SELECT 1 FROM copilot_steer WHERE exercise_id=#{exercise}::uuid AND state='RECEIVED') AND NOT EXISTS(SELECT 1 FROM copilot_compaction WHERE exercise_id=#{exercise}::uuid AND state IN ('WAITING','RUNNING')) AND NOT EXISTS(SELECT 1 FROM copilot_compaction_call WHERE exercise_id=#{exercise}::uuid AND state='DISPATCHED')")
    boolean quiescent(String exercise);

    @Select("SELECT test_task_id::text FROM copilot_test_run WHERE exercise_id=#{exercise}::uuid AND state='WAITING'")
    Set<String> protectedTestRecords(String exercise);
    @Select("SELECT command_task_id::text FROM copilot_command WHERE exercise_id=#{exercise}::uuid AND state='WAITING'")
    Set<String> protectedCommandRecords(String exercise);
    @Delete("DELETE FROM copilot_delegation WHERE exercise_id=#{exercise}::uuid")
    void deleteDelegations(String exercise);
    @Delete("DELETE FROM copilot_command WHERE exercise_id=#{exercise}::uuid")
    void deleteCommands(String exercise);
    @Delete("DELETE FROM copilot_context WHERE exercise_id=#{exercise}::uuid")
    void deleteContext(String exercise);
    @Delete("DELETE FROM copilot_compaction_call WHERE exercise_id=#{exercise}::uuid")
    void deleteCompactionCalls(String exercise);
    @Delete("DELETE FROM copilot_compaction WHERE exercise_id=#{exercise}::uuid")
    void deleteCompactions(String exercise);
    @Delete("DELETE FROM copilot_steer WHERE exercise_id=#{exercise}::uuid")
    void deleteSteers(String exercise);
    @Delete("DELETE FROM copilot_approval_change WHERE exercise_id=#{exercise}::uuid")
    void deleteApprovalChanges(String exercise);
    @Delete("DELETE FROM copilot_approval_mode WHERE exercise_id=#{exercise}::uuid")
    void deleteApprovalMode(String exercise);
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
    @Delete("DELETE FROM copilot_btw WHERE exercise_id=#{exercise}::uuid")
    void deleteBtw(String exercise);
    @Delete("DELETE FROM copilot_followup WHERE exercise_id=#{exercise}::uuid")
    void deleteFollowups(String exercise);
    @Delete("DELETE FROM copilot_followup_resume WHERE exercise_id=#{exercise}::uuid")
    void deleteFollowupResumes(String exercise);
    @Delete("DELETE FROM copilot_fork_message WHERE exercise_id=#{exercise}::uuid")
    void deleteForkMessages(String exercise);
    @Delete("DELETE FROM copilot_conversation WHERE exercise_id=#{exercise}::uuid")
    void deleteConversations(String exercise);
    @Delete("DELETE FROM copilot_task WHERE exercise_id=#{exercise}::uuid")
    void deleteTasks(String exercise);
}
