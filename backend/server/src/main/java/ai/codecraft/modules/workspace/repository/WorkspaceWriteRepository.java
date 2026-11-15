package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.WorkspaceControlEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceMutationEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceTransitionEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceOperationEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkspaceWriteRepository {
    String CONTROL = "exercise_id::text, generation, gate_epoch, gate_state, credential_hash, transition_id::text, fence_id::text";
    String TRANSITION = "id::text, exercise_id::text, kind, expected_epoch, phase, target_hash";
    String MUTATION = "operation_id::text, payload, gate_epoch, not_after, dispatched, revision, changed, failure_code";

    @Insert("INSERT INTO workspace_control(exercise_id) VALUES (#{id}::uuid) ON CONFLICT DO NOTHING")
    void initialize(String id);
    @Select("SELECT " + CONTROL + " FROM workspace_control WHERE exercise_id = #{id}::uuid FOR UPDATE")
    WorkspaceControlEntity lock(String id);
    @Select("SELECT " + CONTROL + " FROM workspace_control WHERE exercise_id = #{id}::uuid")
    WorkspaceControlEntity control(String id);
    @Select("SELECT exercise_id::text FROM workspace_control c JOIN workspace_exercise e ON e.id = c.exercise_id WHERE e.user_id = #{user}::uuid ORDER BY exercise_id")
    List<String> controlled(String user);
    @Insert("INSERT INTO workspace_transition(id, exercise_id, kind, target_hash) VALUES (#{id}::uuid, #{exerciseId}::uuid, #{kind}, #{targetHash})")
    void insertTransition(WorkspaceTransitionEntity transition);
    @Select("SELECT " + TRANSITION + " FROM workspace_transition WHERE id = #{id}::uuid")
    WorkspaceTransitionEntity transition(String id);
    @Select("SELECT " + TRANSITION + " FROM workspace_transition WHERE phase <> 'SUCCEEDED' ORDER BY last_attempt_at, id LIMIT 16")
    List<WorkspaceTransitionEntity> pendingTransitions();
    @Update("UPDATE workspace_transition SET last_attempt_at = clock_timestamp() WHERE id = #{id}::uuid")
    void attempted(String id);
    @Update("UPDATE workspace_control SET transition_id = #{transition}::uuid WHERE exercise_id = #{exercise}::uuid")
    void activate(@Param("exercise") String exercise, @Param("transition") String transition);
    @Update("UPDATE workspace_control SET fence_id = #{fence}::uuid WHERE exercise_id = #{exercise}::uuid")
    void fence(@Param("exercise") String exercise, @Param("fence") String fence);
    @Update("UPDATE workspace_transition SET expected_epoch = #{epoch} WHERE id = #{id}::uuid AND expected_epoch IS NULL")
    void expectation(@Param("id") String id, @Param("epoch") long epoch);
    @Update("UPDATE workspace_transition SET phase = #{phase} WHERE id = #{id}::uuid AND phase <> 'SUCCEEDED'")
    void phase(@Param("id") String id, @Param("phase") String phase);
    @Update("UPDATE workspace_control SET gate_epoch = #{epoch}, gate_state = #{state} WHERE exercise_id = #{id}::uuid")
    void gate(@Param("id") String id, @Param("epoch") long epoch, @Param("state") String state);
    @Update("UPDATE workspace_control SET credential_hash = #{hash}, generation = generation + 1 WHERE exercise_id = #{id}::uuid")
    void holder(@Param("id") String id, @Param("hash") String hash);
    @Insert("INSERT INTO workspace_mutation(operation_id, payload, gate_epoch, not_after) VALUES (#{operationId}::uuid, #{payload}, #{gateEpoch}, #{notAfter})")
    void insertMutation(WorkspaceMutationEntity mutation);
    @Select("SELECT " + MUTATION + " FROM workspace_mutation WHERE operation_id = #{id}::uuid")
    WorkspaceMutationEntity mutation(String id);
    @Update("UPDATE workspace_mutation SET dispatched = true WHERE operation_id = #{id}::uuid AND dispatched = false")
    int dispatch(String id);
    @Select("SELECT " + ExerciseRepository.OPERATION_COLUMNS + " FROM workspace_operation WHERE exercise_id = #{id}::uuid AND kind = 'FILE_BATCH' AND state IN ('PENDING', 'PENDING_RECONCILIATION') ORDER BY created_at, id")
    List<WorkspaceOperationEntity> unresolved(String id);
    @Select("SELECT " + ExerciseRepository.OPERATION_COLUMNS + " FROM workspace_operation WHERE kind = 'FILE_BATCH' AND state IN ('PENDING', 'PENDING_RECONCILIATION') ORDER BY last_attempt_at, created_at, id LIMIT 16")
    List<WorkspaceOperationEntity> pendingMutations();
    @Update("UPDATE workspace_mutation SET revision = #{revision}, changed = #{changed}, failure_code = #{failure} WHERE operation_id = #{id}::uuid")
    void result(@Param("id") String id, @Param("revision") Long revision, @Param("changed") Boolean changed, @Param("failure") String failure);
    @Update("UPDATE workspace_operation SET state='PENDING_RECONCILIATION' WHERE id=#{id}::uuid AND kind='FILE_BATCH' AND state='PENDING'")
    void unknown(String id);
    @Update("UPDATE workspace_exercise SET revision = #{revision}, paths = #{paths}::jsonb, state_version = state_version + 1 WHERE id = #{id}::uuid AND (revision IS NULL OR revision <= #{revision})")
    void observed(@Param("id") String id, @Param("revision") long revision, @Param("paths") String paths);
}
