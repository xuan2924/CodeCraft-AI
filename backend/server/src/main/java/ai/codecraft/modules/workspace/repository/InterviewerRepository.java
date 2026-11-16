package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.InterviewerTaskEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InterviewerRepository {
    String COLUMNS = "id::text,exercise_id::text,user_id::text,session_hash,control_hash,request_key,question,answer,state,reason,platform_version_id::text,phase,revision,created_at,deadline_at,completed_at,usage_receipt";
    @Select("SELECT " + COLUMNS + " FROM workspace_interviewer_task WHERE id=#{id}::uuid")
    InterviewerTaskEntity find(String id);
    @Select("SELECT " + COLUMNS + " FROM workspace_interviewer_task WHERE exercise_id=#{exercise}::uuid AND request_key=#{key}")
    InterviewerTaskEntity receipt(String exercise, String key);
    @Select("SELECT count(*) FROM workspace_interviewer_task WHERE exercise_id=#{exercise}::uuid")
    int count(String exercise);
    @Select("SELECT EXISTS(SELECT 1 FROM workspace_interviewer_task WHERE exercise_id=#{exercise}::uuid AND state IN ('QUEUED','RUNNING'))")
    boolean active(String exercise);
    @Select("SELECT id::text FROM workspace_interviewer_task WHERE state='QUEUED' OR (state='RUNNING' AND deadline_at<=#{now}) ORDER BY deadline_at,id LIMIT 16")
    List<String> pending(Instant now);
    @Select("SELECT " + COLUMNS + " FROM workspace_interviewer_task WHERE exercise_id=#{exercise}::uuid AND (#{before}::uuid IS NULL OR (created_at,id)<(SELECT created_at,id FROM workspace_interviewer_task WHERE exercise_id=#{exercise}::uuid AND id=#{before}::uuid)) ORDER BY created_at DESC,id DESC LIMIT 20")
    List<InterviewerTaskEntity> history(String exercise, String before);
    @Insert("INSERT INTO workspace_interviewer_task(id,exercise_id,user_id,session_hash,control_hash,request_key,question,state,platform_version_id,phase,revision,created_at,deadline_at) VALUES(#{id}::uuid,#{exerciseId}::uuid,#{userId}::uuid,#{sessionHash},#{controlHash},#{requestKey},#{question},'QUEUED',#{platformVersionId}::uuid,#{phase},#{revision},#{createdAt},#{deadlineAt})")
    void insert(InterviewerTaskEntity task);
    @Update("UPDATE workspace_interviewer_task SET price_snapshot=#{price} WHERE id=#{id}::uuid AND state='RUNNING' AND price_snapshot IS NULL")
    int price(String id, String price);
    @Update("UPDATE workspace_interviewer_task SET state='RUNNING',deadline_at=#{deadline} WHERE id=#{id}::uuid AND state='QUEUED'")
    int claim(String id, Instant deadline);
    @Update("UPDATE workspace_interviewer_task SET state=CASE WHEN #{reason}::text IS NULL THEN 'SUCCEEDED' ELSE 'FAILED' END,answer=#{answer},reason=#{reason},completed_at=#{now},usage_receipt=#{usage} WHERE id=#{id}::uuid AND state IN ('QUEUED','RUNNING')")
    void finish(String id, String answer, String reason, Instant now, String usage);
    @Select(value = "UPDATE workspace_interviewer_task SET state='FAILED',reason='INTERVIEWER_INTERRUPTED',completed_at=#{now} WHERE exercise_id=#{exercise}::uuid AND state='QUEUED' RETURNING id::text", affectData = true)
    List<String> pause(String exercise, Instant now);
    @Select("SELECT coalesce(sum(pg_column_size(t)),0)::bigint FROM workspace_interviewer_task t WHERE exercise_id=#{exercise}::uuid")
    long bytes(String exercise);
    @Delete("DELETE FROM workspace_interviewer_task WHERE exercise_id=#{exercise}::uuid")
    void erase(String exercise);
}
