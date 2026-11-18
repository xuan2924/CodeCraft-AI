package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.ExerciseReportEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExerciseReportRepository {
    @Select("SELECT EXISTS(SELECT 1 FROM workspace_exercise WHERE id=#{exercise}::uuid AND retain_until<=#{now})")
    boolean expired(String exercise, Instant now);
    String COLUMNS = "exercise_id::text,id::text,user_id::text,session_hash,platform_version_id::text,final_revision,verification,final_test_id::text,review_skipped,review_text,operation_id::text,generation,state,attempts,attempt_id::text,deadline_at,evidence::text,evaluation::text,reason,created_at,updated_at";
    @Select("SELECT " + COLUMNS + " FROM workspace_report WHERE exercise_id=#{exercise}::uuid")
    ExerciseReportEntity find(String exercise);
    @Select("SELECT exercise_id::text FROM workspace_report WHERE state='RUNNING' AND deadline_at<=#{now} ORDER BY updated_at,exercise_id LIMIT 16")
    List<String> pending(Instant now);
    @Insert("INSERT INTO workspace_report(exercise_id,id,user_id,session_hash,platform_version_id,final_revision,verification,final_test_id,review_skipped,review_text,operation_id,state,created_at,updated_at) VALUES(#{exerciseId}::uuid,#{id}::uuid,#{userId}::uuid,#{sessionHash},#{platformVersionId}::uuid,#{finalRevision},#{verification},#{finalTestId}::uuid,#{reviewSkipped},#{reviewText},#{operationId}::uuid,'QUEUED',#{createdAt},#{updatedAt})")
    void insert(ExerciseReportEntity report);
    @Update("UPDATE workspace_report SET state='PREPARING',attempt_id=#{attempt}::uuid,deadline_at=#{deadline},updated_at=#{now} WHERE exercise_id=#{exercise}::uuid AND (state='QUEUED' OR (state='PREPARING' AND deadline_at<=#{now}))")
    int prepare(String exercise, String attempt, Instant now, Instant deadline);
    @Update("UPDATE workspace_report SET state='RUNNING',attempts=attempts+1,evidence=coalesce(evidence,#{evidence}::jsonb),deadline_at=#{deadline},updated_at=#{now},reason=NULL WHERE exercise_id=#{exercise}::uuid AND generation=#{generation} AND attempt_id=#{attempt}::uuid AND state='PREPARING'")
    int dispatch(String exercise, long generation, String attempt, String evidence, Instant now, Instant deadline);
    @Insert("INSERT INTO workspace_report_attempt(id,exercise_id,generation,state,dispatched_at,price_snapshot) VALUES(#{attempt}::uuid,#{exercise}::uuid,#{generation},'RUNNING',#{now},#{price})")
    void attempt(String attempt, String exercise, long generation, Instant now, String price);
    @Update("UPDATE workspace_report_attempt SET state=#{state},completed_at=#{now},usage_receipt=#{usage},response_text=#{response},reason=#{reason} WHERE id=#{attempt}::uuid AND state='RUNNING'")
    void finishAttempt(String attempt, String state, Instant now, String usage, String response, String reason);
    @Update("UPDATE workspace_report_attempt SET usage_receipt=#{usage} WHERE id=#{attempt}::uuid AND exercise_id=#{exercise}::uuid AND generation=#{generation} AND state='UNKNOWN' AND usage_receipt IS NULL")
    void lateUsage(String attempt, String exercise, long generation, String usage);
    @Update("UPDATE workspace_report SET state=#{state},evaluation=#{evaluation}::jsonb,reason=#{reason},updated_at=#{now},deadline_at=NULL WHERE exercise_id=#{exercise}::uuid AND generation=#{generation}")
    void finish(String exercise, long generation, String state, String evaluation, String reason, Instant now);
    @Update("UPDATE workspace_report SET state='QUEUED',generation=generation+1,attempts=0,attempt_id=NULL,deadline_at=NULL,evaluation=NULL,reason=NULL,session_hash=#{session},operation_id=#{operation}::uuid,updated_at=#{now} WHERE exercise_id=#{exercise}::uuid AND generation=#{generation} AND state IN ('FAILED','UNKNOWN')")
    int retry(String exercise, long generation, String session, String operation, Instant now);
    @Select("SELECT EXISTS(SELECT 1 FROM workspace_report WHERE exercise_id=#{exercise}::uuid AND state IN ('QUEUED','PREPARING','RUNNING'))")
    boolean active(String exercise);
    @Select("SELECT coalesce((SELECT pg_column_size(r) FROM workspace_report r WHERE exercise_id=#{exercise}::uuid),0)::bigint + coalesce((SELECT sum(pg_column_size(a)) FROM workspace_report_attempt a WHERE exercise_id=#{exercise}::uuid),0)::bigint")
    long bytes(String exercise);
    @Delete("DELETE FROM workspace_report_attempt WHERE exercise_id=#{exercise}::uuid")
    void eraseAttempts(String exercise);
    @Delete("DELETE FROM workspace_report WHERE exercise_id=#{exercise}::uuid")
    void erase(String exercise);
}
