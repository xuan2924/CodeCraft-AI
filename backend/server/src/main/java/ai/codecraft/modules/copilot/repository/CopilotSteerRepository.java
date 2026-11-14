package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.entity.CopilotSteerEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CopilotSteerRepository {
    String COLUMNS = "id::text,sequence,task_id::text,exercise_id::text,actor_id::text,actor_auth_version,request_key,fingerprint,text,message_payload,message_id::text,state,turn_id::text,reason,created_at,completed_at";
    @Select("SELECT " + COLUMNS + " FROM copilot_steer WHERE task_id=#{task}::uuid AND request_key=#{key}")
    CopilotSteerEntity receipt(String task, String key);
    @Select("SELECT " + COLUMNS + " FROM copilot_steer WHERE task_id=#{task}::uuid AND (#{before}=0 OR sequence<#{before}) ORDER BY copilot_steer.sequence DESC LIMIT 21")
    List<CopilotSteerEntity> page(String task, long before);
    @Select("SELECT " + COLUMNS + " FROM copilot_steer WHERE task_id=#{task}::uuid AND state='RECEIVED' ORDER BY copilot_steer.sequence")
    List<CopilotSteerEntity> pending(String task);
    @Select("SELECT count(*) FROM copilot_steer WHERE task_id=#{task}::uuid AND state='RECEIVED'")
    int pendingCount(String task);
    @Select("SELECT id::text FROM copilot_steer WHERE task_id=#{task}::uuid AND state='RECEIVED' ORDER BY sequence")
    List<String> pendingIds(String task);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_steer WHERE task_id=#{task}::uuid AND state='RECEIVED' AND message_id IS NULL)")
    boolean unstaged(String task);
    @Insert("""
        INSERT INTO copilot_steer(id,task_id,exercise_id,actor_id,actor_auth_version,request_key,fingerprint,text,message_payload,state,created_at)
        VALUES(#{id}::uuid,#{taskId}::uuid,#{exerciseId}::uuid,#{actorId}::uuid,#{actorAuthVersion},#{requestKey},#{fingerprint},#{text},#{messagePayload},'RECEIVED',#{createdAt})
        """)
    void insert(CopilotSteerEntity steer);
    @Update("UPDATE copilot_steer SET message_id=#{message}::uuid WHERE id=#{id}::uuid AND state='RECEIVED' AND message_id IS NULL")
    int stage(String id, String message);
    @Update("UPDATE copilot_steer SET state='APPLIED',turn_id=#{turn}::uuid,completed_at=#{now} WHERE task_id=#{task}::uuid AND state='RECEIVED' AND message_id IS NOT NULL")
    int applied(String task, String turn, Instant now);
    @Update("UPDATE copilot_steer SET state='NOT_APPLIED',reason=#{reason},completed_at=#{now} WHERE task_id=#{task}::uuid AND state='RECEIVED'")
    int notApplied(String task, String reason, Instant now);
}
