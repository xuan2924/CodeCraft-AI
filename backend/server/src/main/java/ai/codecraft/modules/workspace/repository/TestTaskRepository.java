package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.TestTaskEntity;
import ai.codecraft.modules.workspace.model.dto.TestResultCheckpointDTO;
import ai.codecraft.modules.workspace.model.entity.TestStopEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TestTaskRepository {
    String COLUMNS = "id::text, execution_instance_id::text, exercise_id::text, workspace_instance_id::text, revision, gate_epoch, not_after, target, state, dispatched, quiescent, snapshot::text, host_epoch, go_state_version AS state_version";
    String SUMMARY_COLUMNS = "id::text, execution_instance_id::text, exercise_id::text, workspace_instance_id::text, revision, gate_epoch, not_after, target, state, dispatched, quiescent, jsonb_set(snapshot, '{logs}', '[]')::text AS snapshot, host_epoch, go_state_version AS state_version";
    @Select("SELECT id FROM workspace_execution_admission WHERE id = 1 FOR UPDATE")
    int lockAdmission();
    String OCCUPANTS = "SELECT exercise_id FROM workspace_test_task WHERE NOT quiescent "
        + "UNION ALL SELECT exercise_id FROM workspace_command WHERE NOT pruned AND (NOT quiescent OR synchronization='PENDING' OR snapshot->>'cleanup' IS DISTINCT FROM 'SUCCEEDED' OR snapshot->>'copyCleanup' IS DISTINCT FROM 'SUCCEEDED')";
    @Select("SELECT count(*) FROM (" + OCCUPANTS + ") occupants")
    int occupied();
    @Select("SELECT count(*) FROM (" + OCCUPANTS + ") occupants WHERE exercise_id=#{exercise}::uuid")
    int occupiedExercise(String exercise);
    @Select("SELECT EXISTS (SELECT 1 FROM workspace_command WHERE exercise_id=#{exercise}::uuid AND id=#{task}::uuid AND workspace_instance_id=#{workspace}::uuid AND execution_instance_id=#{execution}::uuid)")
    boolean ownsCommand(@Param("exercise") String exercise, @Param("task") String task, @Param("workspace") String workspace, @Param("execution") String execution);
    @Insert("INSERT INTO workspace_test_task(id, execution_instance_id, exercise_id, workspace_instance_id, revision, gate_epoch, not_after, target) VALUES (#{id}::uuid, #{executionInstanceId}::uuid, #{exerciseId}::uuid, #{workspaceInstanceId}::uuid, #{revision}, #{gateEpoch}, #{notAfter}, #{target})")
    void insert(TestTaskEntity task);
    @Select("SELECT " + COLUMNS + " FROM workspace_test_task WHERE id = #{id}::uuid")
    TestTaskEntity find(String id);
    @Select("<script>SELECT " + COLUMNS + " FROM workspace_test_task WHERE execution_instance_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}::uuid</foreach></script>")
    List<TestTaskEntity> executions(@Param("ids") List<String> ids);
    @Select("SELECT " + COLUMNS + " FROM workspace_test_task WHERE id = #{id}::uuid FOR UPDATE")
    TestTaskEntity lock(String id);
    @Select("SELECT exercise_id::text,state,quiescent,go_state_version AS version,(history_pruning OR history_pruned_at IS NOT NULL) AS retired FROM workspace_test_task WHERE id=#{id}::uuid FOR UPDATE")
    TestResultCheckpointDTO resultCheckpoint(String id);
    @Select("SELECT " + COLUMNS + " FROM workspace_test_task WHERE NOT history_pruning AND history_pruned_at IS NULL AND (NOT quiescent OR snapshot IS NULL OR coalesce(snapshot->>'copyCleanup', 'UNKNOWN') NOT IN ('SUCCEEDED', 'NOT_CREATED') OR snapshot->>'cleanup' IS DISTINCT FROM 'SUCCEEDED') ORDER BY last_attempt_at, id LIMIT 16")
    List<TestTaskEntity> pending();
    @Select("SELECT EXISTS (SELECT 1 FROM workspace_test_task WHERE id = #{id}::uuid AND (history_pruning OR history_pruned_at IS NOT NULL))")
    boolean retired(String id);
    @Select("SELECT count(*) FROM workspace_test_task WHERE exercise_id = #{id}::uuid AND (history_pruning OR history_pruned_at IS NOT NULL)")
    long retiredCount(String id);
    @Select("SELECT count(*) FROM workspace_test_task WHERE exercise_id = #{id}::uuid AND history_pruning")
    long pruningCount(String id);
    @Select("SELECT " + SUMMARY_COLUMNS + " FROM workspace_test_task WHERE exercise_id = #{exercise}::uuid AND NOT history_pruning AND history_pruned_at IS NULL ORDER BY created_at DESC, id DESC LIMIT 100")
    List<TestTaskEntity> recent(String exercise);
    @Update("UPDATE workspace_test_task SET last_attempt_at = clock_timestamp() WHERE id = #{id}::uuid")
    void attempted(String id);
    @Update("UPDATE workspace_test_task SET dispatched = true, host_epoch = #{epoch} WHERE id = #{id}::uuid AND NOT dispatched AND state = 'ACCEPTED'")
    int dispatch(@Param("id") String id, @Param("epoch") String epoch);
    @Update("UPDATE workspace_test_task SET state = #{state}, quiescent = #{quiescent}, snapshot = #{snapshot}::jsonb WHERE id = #{id}::uuid")
    void observed(@Param("id") String id, @Param("state") String state, @Param("quiescent") boolean quiescent, @Param("snapshot") String snapshot);

    @Select("SELECT go_state_version FROM workspace_test_task WHERE id = #{id}::uuid")
    long goVersion(String id);
    @Update("UPDATE workspace_test_task SET go_state_version = #{version}, go_stream_id = #{stream}, go_sequence = #{sequence} WHERE id = #{id}::uuid")
    void updateGoVersion(@Param("id") String id, @Param("version") long version, @Param("stream") String stream, @Param("sequence") long sequence);

    @Insert("INSERT INTO workspace_test_stop(id, task_id, gate_epoch, not_after, owner_recovery) VALUES (#{id}::uuid, #{taskId}::uuid, #{gateEpoch}, #{notAfter}, #{ownerRecovery})")
    void insertStop(TestStopEntity stop);
    @Select("SELECT id::text, task_id::text, gate_epoch, not_after, state, owner_recovery FROM workspace_test_stop WHERE task_id = #{task}::uuid AND state IN ('PENDING', 'SUCCEEDED') ORDER BY not_after, id LIMIT 1")
    TestStopEntity acceptedStop(String task);
    @Select("SELECT id::text, task_id::text, gate_epoch, not_after, state, owner_recovery FROM workspace_test_stop WHERE id = #{id}::uuid")
    TestStopEntity stop(String id);
    @Select("SELECT id::text, task_id::text, gate_epoch, not_after, state, owner_recovery FROM workspace_test_stop WHERE state = 'PENDING' ORDER BY last_attempt_at, id LIMIT 16")
    List<TestStopEntity> pendingStops();
    @Update("UPDATE workspace_test_stop SET owner_recovery = true WHERE id = #{id}::uuid AND state = 'PENDING'")
    void recoverStopAuthority(String id);
    @Update("UPDATE workspace_test_stop SET state = #{state}, last_attempt_at = clock_timestamp() WHERE id = #{id}::uuid AND state = 'PENDING'")
    void stopState(@Param("id") String id, @Param("state") String state);
}
