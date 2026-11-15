package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.entity.CopilotFollowupEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CopilotFollowupRepository {
    String COLUMNS = "id::text,sequence,exercise_id::text,conversation_id::text,user_id::text,session_hash,control_hash,request_key,text,anchor_task_id::text,resume_after::text,state,reason,task_id::text,created_at,completed_at,generation,anchor_sequence";
    @Select("SELECT " + COLUMNS + " FROM copilot_followup WHERE id=#{id}::uuid")
    CopilotFollowupEntity find(String id);
    @Select("SELECT " + COLUMNS + " FROM copilot_followup WHERE exercise_id=#{exercise}::uuid AND request_key=#{key}")
    CopilotFollowupEntity receipt(String exercise, String key);
    @Select("SELECT " + COLUMNS + " FROM copilot_followup WHERE conversation_id=#{conversation}::uuid AND (state IN ('WAITING','PAUSED') OR id IN (SELECT id FROM copilot_followup WHERE conversation_id=#{conversation}::uuid ORDER BY sequence DESC LIMIT 100)) ORDER BY sequence LIMIT 132")
    List<CopilotFollowupEntity> recent(String conversation);
    @Select("SELECT " + COLUMNS + " FROM copilot_followup WHERE conversation_id=#{conversation}::uuid AND state IN ('WAITING','PAUSED') ORDER BY sequence LIMIT 1")
    CopilotFollowupEntity head(String conversation);
    @Select("SELECT count(*) FROM copilot_followup WHERE conversation_id=#{conversation}::uuid AND state IN ('WAITING','PAUSED')")
    int pendingCount(String conversation);
    @Select("SELECT task_id::text FROM copilot_followup WHERE conversation_id=#{conversation}::uuid AND sequence<#{sequence} AND sequence>#{after} AND state='DISPATCHED' ORDER BY sequence DESC LIMIT 1")
    String previousTask(String conversation, long sequence, long after);
    @Select("SELECT COALESCE(max(sequence),0) FROM copilot_followup WHERE conversation_id=#{conversation}::uuid AND state='DISPATCHED'")
    long dispatchedSequence(String conversation);
    @Select("""
        SELECT q.id::text FROM copilot_followup q JOIN workspace_exercise e ON e.id=q.exercise_id
        WHERE (q.state IN ('WAITING','PAUSED') AND (e.state<>'ACTIVE' OR e.deadline_at<=#{now}))
        OR (q.state='WAITING'
          AND NOT EXISTS(SELECT 1 FROM copilot_followup p WHERE p.conversation_id=q.conversation_id AND p.sequence<q.sequence AND p.state IN ('WAITING','PAUSED'))
          AND NOT EXISTS(SELECT 1 FROM copilot_task t WHERE t.exercise_id=q.exercise_id AND t.state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','STOPPING'))
          AND NOT EXISTS(SELECT 1 FROM copilot_compaction c WHERE c.exercise_id=q.exercise_id AND c.state IN ('WAITING','RUNNING')))
        ORDER BY q.sequence LIMIT 32
        """)
    List<String> candidates(Instant now);
    @Insert("INSERT INTO copilot_followup(id,exercise_id,conversation_id,user_id,session_hash,control_hash,request_key,text,anchor_task_id,anchor_sequence,state,created_at) VALUES(#{id}::uuid,#{exerciseId}::uuid,#{conversationId}::uuid,#{userId}::uuid,#{sessionHash},#{controlHash},#{requestKey},#{text},#{anchorTaskId}::uuid,#{anchorSequence},'WAITING',#{createdAt})")
    void insert(CopilotFollowupEntity row);
    @Update("UPDATE copilot_followup SET state='DISPATCHED',task_id=#{task}::uuid,reason=NULL,completed_at=#{now} WHERE id=#{id}::uuid AND state='WAITING'")
    int dispatched(String id, String task, Instant now);
    @Update("UPDATE copilot_followup SET state='PAUSED',reason=#{reason} WHERE id=#{id}::uuid AND state='WAITING'")
    void pause(String id, String reason);
    @Update("UPDATE copilot_followup SET state='CANCELLED',reason=#{reason},completed_at=#{now} WHERE id=#{id}::uuid AND state IN ('WAITING','PAUSED')")
    void cancel(String id, String reason, Instant now);
    @Update("UPDATE copilot_followup SET state='CANCELLED',reason=#{reason},completed_at=#{now} WHERE exercise_id=#{exercise}::uuid AND state IN ('WAITING','PAUSED')")
    void cancelExercise(String exercise, String reason, Instant now);
    @Update("UPDATE copilot_followup SET state='WAITING',reason=NULL,generation=generation+1,session_hash=#{session},control_hash=#{control},resume_after=#{predecessor}::uuid WHERE conversation_id=#{conversation}::uuid AND state IN ('WAITING','PAUSED')")
    void resume(String conversation, String session, String control, String predecessor);
    @Select("SELECT conversation_id::text FROM copilot_followup_resume WHERE exercise_id=#{exercise}::uuid AND request_key=#{key}")
    String resumeReceipt(String exercise, String key);
    @Insert("INSERT INTO copilot_followup_resume(id,exercise_id,conversation_id,request_key,created_at) VALUES(#{id}::uuid,#{exercise}::uuid,#{conversation}::uuid,#{key},#{now})")
    void resumed(String id, String exercise, String conversation, String key, Instant now);
}
