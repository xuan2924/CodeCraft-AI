package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.CommandTaskEntity;
import ai.codecraft.modules.workspace.model.dto.CommandEvidenceDTO;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CommandTaskRepository {
    String IDENTITY = "id::text, exercise_id::text, user_id::text, workspace_instance_id::text, execution_instance_id::text, session_hash, actor_auth_version, request_key, fingerprint, command_text, working_directory, revision, gate_epoch, not_after, host_epoch, state, dispatched, stop_requested, quiescent, synchronization, ";
    String TAIL = ", state_version, pruning, pruned, created_at, completed_at";
    String COLUMNS = IDENTITY + "snapshot::text" + TAIL;
    String SUMMARY = IDENTITY + "jsonb_set(snapshot, '{logs}', '[]')::text AS snapshot" + TAIL;

    @Select("SELECT " + COLUMNS + " FROM workspace_command WHERE id=#{id}::uuid")
    CommandTaskEntity find(String id);
    @Select("SELECT state_version AS version,snapshot::text AS payload FROM workspace_command WHERE id=#{id}::uuid AND exercise_id=#{exercise}::uuid AND NOT pruning AND NOT pruned AND quiescent AND synchronization<>'PENDING' AND snapshot->>'cleanup'='SUCCEEDED' AND snapshot->>'copyCleanup'='SUCCEEDED'")
    CommandEvidenceDTO confirmed(@Param("exercise") String exercise, @Param("id") String id);
    @Select("SELECT " + COLUMNS + " FROM workspace_command WHERE id=#{id}::uuid FOR UPDATE")
    CommandTaskEntity lock(String id);
    @Select("SELECT " + COLUMNS + " FROM workspace_command WHERE exercise_id=#{exercise}::uuid AND request_key=#{key}")
    CommandTaskEntity receipt(@Param("exercise") String exercise, @Param("key") String key);
    @Select("SELECT " + SUMMARY + " FROM workspace_command WHERE exercise_id=#{exercise}::uuid AND NOT pruning AND NOT pruned AND (#{after}::uuid IS NULL OR (created_at,id)<(SELECT created_at,id FROM workspace_command WHERE id=#{after}::uuid AND exercise_id=#{exercise}::uuid)) ORDER BY created_at DESC, id DESC LIMIT 21")
    List<CommandTaskEntity> recent(@Param("exercise") String exercise, @Param("after") String after);
    @Select("SELECT " + COLUMNS + " FROM workspace_command WHERE NOT pruned AND (NOT quiescent OR synchronization='PENDING' OR snapshot->>'cleanup' IS DISTINCT FROM 'SUCCEEDED' OR snapshot->>'copyCleanup' IS DISTINCT FROM 'SUCCEEDED') ORDER BY last_checked_at, created_at, id LIMIT 16")
    List<CommandTaskEntity> pending();
    @Insert("""
        INSERT INTO workspace_command(id,exercise_id,user_id,workspace_instance_id,execution_instance_id,session_hash,actor_auth_version,
            request_key,fingerprint,command_text,working_directory,revision,gate_epoch,not_after,created_at)
        VALUES(#{id}::uuid,#{exerciseId}::uuid,#{userId}::uuid,#{workspaceInstanceId}::uuid,#{executionInstanceId}::uuid,
            #{sessionHash},#{actorAuthVersion},#{requestKey},#{fingerprint},#{commandText},#{workingDirectory},#{revision},#{gateEpoch},#{notAfter},#{createdAt})
        """)
    void insert(CommandTaskEntity command);
    @Update("UPDATE workspace_command SET dispatched=true,host_epoch=#{epoch},state='STARTING',last_checked_at=#{now} WHERE id=#{id}::uuid AND NOT dispatched AND NOT stop_requested AND NOT quiescent")
    int dispatch(@Param("id") String id, @Param("epoch") String epoch, @Param("now") Instant now);
    @Update("UPDATE workspace_command SET stop_requested=true,snapshot=jsonb_set(snapshot, '{stopRequested}', 'true') WHERE id=#{id}::uuid AND NOT quiescent")
    void stop(String id);
    @Update("UPDATE workspace_command SET last_checked_at=#{now} WHERE id=#{id}::uuid")
    void checked(@Param("id") String id, @Param("now") Instant now);
    @Update("""
        UPDATE workspace_command SET state=#{state},quiescent=#{quiescent},synchronization=#{synchronization},snapshot=#{snapshot}::jsonb,
            state_version=#{version},completed_at=#{completed},last_checked_at=#{now}
        WHERE id=#{id}::uuid AND NOT pruned AND state_version<=#{version}
        """)
    void observed(@Param("id") String id, @Param("state") String state, @Param("quiescent") boolean quiescent,
        @Param("synchronization") String synchronization, @Param("snapshot") String snapshot, @Param("version") long version,
        @Param("completed") Instant completed, @Param("now") Instant now);
    @Select("SELECT " + COLUMNS + """
         FROM workspace_command WHERE NOT pruned AND (pruning OR id IN (
            SELECT id FROM (SELECT id,row_number() OVER(PARTITION BY exercise_id ORDER BY created_at DESC,id DESC) AS position
                FROM workspace_command WHERE NOT pruning AND NOT pruned AND quiescent AND synchronization IN ('SYNCED','UNCHANGED')
                    AND snapshot->>'cleanup'='SUCCEEDED' AND snapshot->>'copyCleanup'='SUCCEEDED') ranked WHERE position>20))
         ORDER BY last_checked_at,id LIMIT 4
        """)
    List<CommandTaskEntity> evictionCandidates();
    @Select("SELECT " + COLUMNS + " FROM workspace_command WHERE exercise_id=#{exercise}::uuid AND NOT pruned AND quiescent AND synchronization IN ('SYNCED','UNCHANGED') AND snapshot->>'cleanup'='SUCCEEDED' AND snapshot->>'copyCleanup'='SUCCEEDED' ORDER BY created_at,id LIMIT 4")
    List<CommandTaskEntity> pressureCandidates(String exercise);
    @Update("UPDATE workspace_command SET pruning=true,last_checked_at=#{now} WHERE id=#{id}::uuid AND NOT pruned AND quiescent AND synchronization IN ('SYNCED','UNCHANGED')")
    int beginPruning(@Param("id") String id, @Param("now") Instant now);
    @Update("UPDATE workspace_command SET pruning=false,pruned=true,snapshot=NULL WHERE id=#{id}::uuid AND quiescent AND synchronization IN ('SYNCED','UNCHANGED')")
    void pruned(String id);
}
