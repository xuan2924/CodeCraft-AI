package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.entity.CopilotCommandEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CopilotCommandRepository {
    String COLUMNS = "id::text,sequence,task_id::text,exercise_id::text,user_id::text,workspace_instance_id::text,base_revision,user_sequence,"
        + "command_text,working_directory,fingerprint,mode,mode_version,state,command_task_id::text,decision_key,decision_fingerprint,decision,"
        + "decision_actor::text,actor_auth_version,reason,result_payload,created_at,decided_at,completed_at";
    @Select("SELECT " + COLUMNS + " FROM copilot_command WHERE id=#{id}::uuid")
    CopilotCommandEntity find(String id);
    @Select("SELECT " + COLUMNS + " FROM copilot_command WHERE task_id=#{task}::uuid AND state IN ('PENDING','WAITING')")
    CopilotCommandEntity pending(String task);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_command WHERE task_id=#{task}::uuid AND state='WAITING')")
    boolean waiting(String task);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_command WHERE task_id=#{task}::uuid AND user_sequence=#{goal} AND fingerprint=#{fingerprint} AND decision='REJECT')")
    boolean rejected(@Param("task") String task, @Param("goal") long goal, @Param("fingerprint") String fingerprint);
    @Select("SELECT " + COLUMNS + " FROM copilot_command WHERE task_id=#{task}::uuid AND (#{before}=0 OR sequence<#{before}) ORDER BY sequence DESC LIMIT 21")
    List<CopilotCommandEntity> page(@Param("task") String task, @Param("before") long before);
    @Select("SELECT task_id::text FROM copilot_command WHERE state='PENDING' AND mode='AUTO' ORDER BY sequence LIMIT 100")
    List<String> automaticPending();
    @Insert("""
        INSERT INTO copilot_command(id,task_id,exercise_id,user_id,workspace_instance_id,base_revision,user_sequence,
            command_text,working_directory,fingerprint,mode,mode_version,created_at)
        VALUES(#{id}::uuid,#{taskId}::uuid,#{exerciseId}::uuid,#{userId}::uuid,#{workspaceInstanceId}::uuid,#{baseRevision},#{userSequence},
            #{commandText},#{workingDirectory},#{fingerprint},#{mode},#{modeVersion},#{createdAt})
        """)
    void insert(CopilotCommandEntity command);
    @Update("""
        UPDATE copilot_command SET decision_key=#{key},decision_fingerprint=#{fingerprint},decision=#{decision},
            decision_actor=#{actor}::uuid,actor_auth_version=#{version},reason=#{reason},decided_at=#{now}
        WHERE id=#{id}::uuid AND state='PENDING' AND decision IS NULL
        """)
    int decide(@Param("id") String id, @Param("key") String key, @Param("fingerprint") String fingerprint,
        @Param("decision") String decision, @Param("actor") String actor, @Param("version") long version,
        @Param("reason") String reason, @Param("now") Instant now);
    @Update("UPDATE copilot_command SET state='WAITING',command_task_id=#{child}::uuid WHERE id=#{id}::uuid AND state='PENDING' AND decision='APPROVE'")
    int bind(@Param("id") String id, @Param("child") String child);
    @Update("UPDATE copilot_command SET state='RESOLVED',result_payload=#{payload},completed_at=#{now} WHERE id=#{id}::uuid AND state IN ('PENDING','WAITING')")
    int resolve(@Param("id") String id, @Param("payload") String payload, @Param("now") Instant now);
    @Update("UPDATE copilot_command SET state='RESOLVED',result_payload=jsonb_build_object('code','NOT_EXECUTED','reason',#{reason}::text,'result',NULL,'logTruncated',false,'omittedLogBytes','0')::text,completed_at=#{now} WHERE task_id=#{task}::uuid AND state='PENDING'")
    void cancelPending(@Param("task") String task, @Param("reason") String reason, @Param("now") Instant now);
}
