package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.entity.TestStopEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TestTaskRepository {
    String COLUMNS = "id::text, execution_instance_id::text, exercise_id::text, workspace_instance_id::text, revision, gate_epoch, not_after, target, state, dispatched, quiescent, snapshot::text";
    String SUMMARY_COLUMNS = "id::text, execution_instance_id::text, exercise_id::text, workspace_instance_id::text, revision, gate_epoch, not_after, target, state, dispatched, quiescent, jsonb_set(snapshot, '{logs}', '[]')::text AS snapshot";
    @Select("SELECT id FROM workspace_execution_admission WHERE id = 1 FOR UPDATE")
    int lockAdmission();
    @Select("SELECT count(*) FROM workspace_test_task WHERE NOT quiescent")
    int occupied();
    @Insert("INSERT INTO workspace_test_task(id, execution_instance_id, exercise_id, workspace_instance_id, revision, gate_epoch, not_after, target) VALUES (#{id}::uuid, #{executionInstanceId}::uuid, #{exerciseId}::uuid, #{workspaceInstanceId}::uuid, #{revision}, #{gateEpoch}, #{notAfter}, #{target})")
    void insert(TestTaskEntity task);
    @Select("SELECT " + COLUMNS + " FROM workspace_test_task WHERE id = #{id}::uuid")
    TestTaskEntity find(String id);
    @Select("SELECT " + COLUMNS + " FROM workspace_test_task WHERE id = #{id}::uuid FOR UPDATE")
    TestTaskEntity lock(String id);
    @Select("SELECT " + COLUMNS + " FROM workspace_test_task WHERE NOT quiescent OR snapshot IS NULL OR snapshot->>'copyCleanup' IN ('PENDING', 'FAILED') OR snapshot->>'cleanup' = 'FAILED' ORDER BY last_attempt_at, id LIMIT 16")
    List<TestTaskEntity> pending();
    @Select("SELECT " + SUMMARY_COLUMNS + " FROM workspace_test_task WHERE exercise_id = #{exercise}::uuid ORDER BY created_at DESC, id DESC LIMIT 20")
    List<TestTaskEntity> recent(String exercise);
    @Update("UPDATE workspace_test_task SET last_attempt_at = clock_timestamp() WHERE id = #{id}::uuid")
    void attempted(String id);
    @Update("UPDATE workspace_test_task SET dispatched = true WHERE id = #{id}::uuid AND NOT dispatched AND state = 'ACCEPTED'")
    int dispatch(String id);
    @Update("UPDATE workspace_test_task SET state = #{state}, quiescent = #{quiescent}, snapshot = #{snapshot}::jsonb WHERE id = #{id}::uuid")
    void observed(@Param("id") String id, @Param("state") String state, @Param("quiescent") boolean quiescent, @Param("snapshot") String snapshot);

    @Select("SELECT go_state_version FROM workspace_test_task WHERE id = #{id}::uuid")
    long goVersion(String id);
    @Update("UPDATE workspace_test_task SET go_state_version = #{version}, go_stream_id = #{stream}, go_sequence = #{sequence} WHERE id = #{id}::uuid")
    void updateGoVersion(@Param("id") String id, @Param("version") long version, @Param("stream") String stream, @Param("sequence") long sequence);

    @Insert("INSERT INTO workspace_test_stop(id, task_id, gate_epoch, not_after) VALUES (#{id}::uuid, #{taskId}::uuid, #{gateEpoch}, #{notAfter})")
    void insertStop(TestStopEntity stop);
    @Select("SELECT id::text, task_id::text, gate_epoch, not_after, state FROM workspace_test_stop WHERE id = #{id}::uuid")
    TestStopEntity stop(String id);
    @Select("SELECT id::text, task_id::text, gate_epoch, not_after, state FROM workspace_test_stop WHERE state = 'PENDING' ORDER BY last_attempt_at, id LIMIT 16")
    List<TestStopEntity> pendingStops();
    @Update("UPDATE workspace_test_stop SET state = #{state}, last_attempt_at = clock_timestamp() WHERE id = #{id}::uuid AND state = 'PENDING'")
    void stopState(@Param("id") String id, @Param("state") String state);
}
