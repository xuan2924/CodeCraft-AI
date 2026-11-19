package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.StorageExecutionEntity;
import ai.codecraft.modules.workspace.model.entity.StorageMeterEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StorageCapacityRepository {
    @Insert("INSERT INTO workspace_storage_budget(exercise_id) VALUES (#{id}::uuid) ON CONFLICT DO NOTHING")
    int initialize(String id);
    @Select("SELECT exercise_id::text, meter_epoch, sequence, bytes, checked_at FROM workspace_storage_budget WHERE exercise_id = #{id}::uuid FOR UPDATE")
    StorageMeterEntity lock(String id);
    @Update("UPDATE workspace_storage_budget SET meter_epoch = #{epoch}, sequence = #{sequence}, bytes = #{bytes}, checked_at = #{now}, last_attempt_at = #{now} WHERE exercise_id = #{id}::uuid")
    void observed(String id, String epoch, long sequence, long bytes, Instant now);
    @Update("UPDATE workspace_storage_budget SET last_attempt_at = #{now} WHERE exercise_id = #{id}::uuid")
    void attempted(String id, Instant now);
    @Insert("INSERT INTO workspace_storage_reservation(operation_id, exercise_id, growth_bytes, created_at) VALUES (#{operation}::uuid, #{exercise}::uuid, #{growth}, #{now})")
    void reserve(String operation, String exercise, long growth, Instant now);
    @Select("SELECT coalesce(sum(growth_bytes), 0)::bigint FROM workspace_storage_reservation WHERE exercise_id = #{id}::uuid")
    long reservedBytes(String id);
    String RESERVATION_SCOPE = " FROM workspace_storage_reservation r LEFT JOIN workspace_operation o ON o.id = r.operation_id "
        + "LEFT JOIN workspace_test_task t ON t.id = o.id ";
    String DELETED = "EXISTS (SELECT 1 FROM workspace_deletion d WHERE d.exercise_id = r.exercise_id AND d.state = 'SUCCEEDED')";
    String SETTLED = "(o.state IN ('SUCCEEDED', 'REJECTED') AND (o.kind NOT IN ('START_TEST', 'FINAL_TEST') OR "
        + "(t.quiescent AND t.snapshot->>'cleanup' = 'SUCCEEDED' AND t.snapshot->>'copyCleanup' IN ('SUCCEEDED', 'NOT_CREATED'))))";

    // Active light credits roll forward until the next calibration; open heavy work is not releasable yet.
    @Select("SELECT b.exercise_id::text FROM workspace_storage_budget b JOIN workspace_exercise e ON e.id = b.exercise_id "
        + "WHERE EXISTS (SELECT 1" + RESERVATION_SCOPE + "WHERE r.exercise_id = b.exercise_id AND (" + DELETED + " OR (" + SETTLED
        + " AND (e.state = 'ENDED' OR o.kind IN ('PREPARE','PREPARATION_RETRY','START_TEST','FINAL_TEST','START_COMMAND'))))) "
        + "ORDER BY b.last_attempt_at, b.exercise_id LIMIT 16")
    List<String> pendingExercises();
    @Select("SELECT r.operation_id::text" + RESERVATION_SCOPE + "WHERE r.exercise_id = #{id}::uuid AND (" + DELETED + " OR " + SETTLED + ")")
    List<String> settledOperations(String id);
    @Delete("<script>DELETE FROM workspace_storage_reservation WHERE operation_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}::uuid</foreach></script>")
    int release(@org.apache.ibatis.annotations.Param("ids") List<String> ids);
    @Insert("INSERT INTO workspace_storage_instance(workspace_instance_id, exercise_id) VALUES (#{instance}::uuid, #{exercise}::uuid) ON CONFLICT DO NOTHING")
    int instance(String exercise, String instance);
    @Insert("INSERT INTO workspace_storage_execution(exercise_id, task_id, execution_instance_id, workspace_instance_id) VALUES (#{exerciseId}::uuid, #{taskId}::uuid, #{executionInstanceId}::uuid, #{workspaceInstanceId}::uuid) ON CONFLICT DO NOTHING")
    int execution(StorageExecutionEntity execution);
    @Insert("INSERT INTO workspace_storage_instance(workspace_instance_id, exercise_id) SELECT workspace_instance_id, id FROM workspace_exercise WHERE id = #{id}::uuid UNION SELECT workspace_instance_id, exercise_id FROM workspace_operation WHERE exercise_id = #{id}::uuid ON CONFLICT DO NOTHING")
    void syncInstances(String id);
    @Insert("INSERT INTO workspace_storage_execution(exercise_id, task_id, execution_instance_id, workspace_instance_id) SELECT exercise_id, id, execution_instance_id, workspace_instance_id FROM workspace_test_task WHERE exercise_id = #{id}::uuid ON CONFLICT DO NOTHING")
    void syncExecutions(String id);
    @Select("SELECT workspace_instance_id::text FROM workspace_storage_instance WHERE exercise_id = #{id}::uuid ORDER BY workspace_instance_id")
    List<String> instances(String id);
    @Select("SELECT exercise_id::text, task_id::text, execution_instance_id::text, workspace_instance_id::text FROM workspace_storage_execution WHERE exercise_id = #{id}::uuid ORDER BY execution_instance_id")
    List<StorageExecutionEntity> executions(String id);

    @Select("""
        SELECT coalesce(sum(n),0)::bigint FROM (
          SELECT sum(pg_column_size(t)) AS n FROM workspace_exercise t WHERE id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_operation t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_control t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_transition t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_mutation t JOIN workspace_operation o ON o.id = t.operation_id WHERE o.exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_test_task t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_command t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_test_stop t JOIN workspace_test_task x ON x.id = t.task_id WHERE x.exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_task_stream t JOIN workspace_test_task x ON x.id = t.task_id WHERE x.exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_task_event t JOIN workspace_test_task x ON x.id = t.task_id WHERE x.exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_final_task t JOIN workspace_test_task x ON x.id = t.task_id WHERE x.exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_finalization t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_deletion t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_storage_budget t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_storage_reservation t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_storage_instance t WHERE exercise_id = #{id}::uuid
          UNION ALL SELECT sum(pg_column_size(t)) FROM workspace_storage_execution t WHERE exercise_id = #{id}::uuid
        ) attributed
        """)
    long databaseBytes(String id);
}
