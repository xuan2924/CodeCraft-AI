package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.ExerciseEntity;
import ai.codecraft.modules.workspace.model.dto.PreparationAuthorizationDTO;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExerciseRepository {
    String EXERCISE_COLUMNS = "id::text, user_id::text, problem_version_id::text, workspace_instance_id::text, preparation_operation_id::text, state, revision, paths::text, state_version, created_at, started_at, deadline_at, preparation_failure_reason, end_reason, cleanup_state";
    @Select("SELECT EXISTS (SELECT 1 FROM workspace_deletion WHERE exercise_id = #{id}::uuid)")
    boolean deleted(String id);

    String OPERATION_COLUMNS = "id::text, user_id::text, exercise_id::text, workspace_instance_id::text, kind, target, request_key, fingerprint, state, created_at";

    @Select("SELECT " + EXERCISE_COLUMNS + " FROM workspace_exercise WHERE user_id = #{user}::uuid AND state = 'PREPARING' AND (#{session}::text IS NULL OR preparation_session_hash = #{session} OR preparation_session_hash IS NULL)")
    List<ExerciseEntity> pendingForRevocation(@Param("user") String user, @Param("session") String session);

    @Update("UPDATE workspace_exercise SET preparation_session_hash = #{session}, preparation_not_after = #{notAfter} WHERE id = #{id}::uuid")
    void bindPreparationSession(@Param("id") String id, @Param("session") String session, @Param("notAfter") Instant notAfter);

    @Select("SELECT preparation_session_hash AS session_hash, preparation_not_after AS not_after FROM workspace_exercise WHERE id = #{id}::uuid")
    PreparationAuthorizationDTO preparationAuthorization(String id);

    @Select("SELECT " + EXERCISE_COLUMNS + " FROM workspace_exercise WHERE preparation_operation_id = #{id}::uuid")
    ExerciseEntity findPreparation(String id);

    @Select("SELECT " + EXERCISE_COLUMNS + " FROM workspace_exercise WHERE id = #{id}::uuid")
    ExerciseEntity find(String id);

    @Select("SELECT " + EXERCISE_COLUMNS + " FROM workspace_exercise WHERE user_id = #{user}::uuid AND state <> 'ENDED'")
    ExerciseEntity current(String user);

    @Select("SELECT count(*) FROM workspace_exercise e WHERE user_id = #{user}::uuid AND state = 'ENDED' AND NOT EXISTS (SELECT 1 FROM workspace_deletion d WHERE d.exercise_id = e.id)")
    int retainedHistory(String user);
    @Update("UPDATE workspace_exercise SET retention_seconds = #{seconds} WHERE id = #{id}::uuid AND state = 'PREPARING'")
    void retention(@Param("id") String id, @Param("seconds") long seconds);
    @Update("UPDATE workspace_exercise SET ended_at = #{now}, retain_until = #{now} + retention_seconds * interval '1 second' WHERE id = #{id}::uuid AND state = 'ENDED' AND ended_at IS NULL")
    void endedAt(@Param("id") String id, @Param("now") Instant now);

    @Insert("""
        INSERT INTO workspace_exercise(id, user_id, problem_version_id, workspace_instance_id, preparation_operation_id, state, created_at)
        VALUES (#{id}::uuid, #{userId}::uuid, #{problemVersionId}::uuid, #{workspaceInstanceId}::uuid, #{preparationOperationId}::uuid, 'PREPARING', #{createdAt})
        """)
    void insert(ExerciseEntity exercise);

    @Select("SELECT " + OPERATION_COLUMNS + " FROM workspace_operation WHERE user_id = #{user}::uuid AND kind = #{kind} AND target = #{target} AND request_key = #{key}")
    WorkspaceOperationEntity receipt(@Param("user") String user, @Param("kind") String kind, @Param("target") String target, @Param("key") String key);

    @Select("SELECT " + OPERATION_COLUMNS + " FROM workspace_operation WHERE id = #{id}::uuid")
    WorkspaceOperationEntity operation(String id);

    @Insert("""
        INSERT INTO workspace_operation(id, user_id, exercise_id, workspace_instance_id, kind, target, request_key, fingerprint, state, created_at)
        VALUES (#{id}::uuid, #{userId}::uuid, #{exerciseId}::uuid, #{workspaceInstanceId}::uuid, #{kind}, #{target}, #{requestKey}, #{fingerprint}, #{state}, #{createdAt})
        """)
    void insertOperation(WorkspaceOperationEntity operation);

    @Select("SELECT " + OPERATION_COLUMNS + " FROM workspace_operation WHERE kind IN ('PREPARE', 'PREPARATION_RETRY', 'ABANDON') AND state IN ('PENDING', 'PENDING_RECONCILIATION') ORDER BY last_attempt_at, created_at, id LIMIT 16")
    List<WorkspaceOperationEntity> pending();

    @Update("UPDATE workspace_operation SET last_attempt_at = clock_timestamp() WHERE id = #{id}::uuid AND state IN ('PENDING', 'PENDING_RECONCILIATION')")
    void attempted(String id);

    @Update("""
        UPDATE workspace_exercise SET state = 'READY', revision = #{revision}, paths = #{paths}::jsonb, state_version = state_version + 1
        WHERE id = #{exercise}::uuid AND workspace_instance_id = #{instance}::uuid AND preparation_operation_id = #{operation}::uuid AND state = 'PREPARING'
        """)
    int prepared(@Param("exercise") String exercise, @Param("instance") String instance,
                 @Param("operation") String operation, @Param("revision") long revision, @Param("paths") String paths);

    @Update("""
        UPDATE workspace_exercise SET state = 'ACTIVE', started_at = #{started}, deadline_at = #{deadline}, state_version = state_version + 1
        WHERE id = #{id}::uuid AND state = 'READY'
        """)
    int start(@Param("id") String id, @Param("started") Instant started, @Param("deadline") Instant deadline);

    @Update("""
        UPDATE workspace_exercise SET state = 'ENDED', revision = NULL, paths = '[]'::jsonb, state_version = state_version + 1,
            end_reason = 'PREPARATION_ABANDONED', cleanup_state = 'PENDING'
        WHERE id = #{id}::uuid AND state IN ('READY', 'PREPARATION_FAILED')
        """)
    int abandon(String id);

    @Update("""
        UPDATE workspace_exercise SET state = 'PREPARATION_FAILED', state_version = state_version + 1, cleanup_state = 'SUCCEEDED',
            preparation_failure_reason = coalesce(preparation_failure_reason, 'PREPARATION_CANCELLED')
        WHERE id = #{exercise}::uuid AND preparation_operation_id = #{operation}::uuid AND state = 'PREPARING'
        """)
    int preparationFailed(@Param("exercise") String exercise, @Param("operation") String operation);

    @Update("""
        UPDATE workspace_exercise SET state = 'PREPARING', workspace_instance_id = #{instance}::uuid,
            preparation_operation_id = #{operation}::uuid, revision = NULL, paths = '[]'::jsonb, state_version = state_version + 1,
            preparation_failure_reason = NULL, cleanup_state = NULL
        WHERE id = #{id}::uuid AND state = 'PREPARATION_FAILED'
        """)
    int retry(@Param("id") String id, @Param("instance") String instance, @Param("operation") String operation);

    @Update("""
        UPDATE workspace_exercise SET preparation_failure_reason = coalesce(preparation_failure_reason, #{reason}), cleanup_state = 'PENDING'
        WHERE id = #{exercise}::uuid AND preparation_operation_id = #{operation}::uuid AND state = 'PREPARING'
        """)
    void failureIntent(@Param("exercise") String exercise, @Param("operation") String operation, @Param("reason") String reason);

    @Update("UPDATE workspace_exercise SET cleanup_state = 'SUCCEEDED' WHERE id = #{exercise}::uuid AND workspace_instance_id = #{instance}::uuid AND state = 'ENDED'")
    void cleaned(@Param("exercise") String exercise, @Param("instance") String instance);

    @Update("UPDATE workspace_operation SET state = #{state} WHERE id = #{id}::uuid AND state IN ('PENDING', 'PENDING_RECONCILIATION')")
    void operationState(@Param("id") String id, @Param("state") String state);
}
