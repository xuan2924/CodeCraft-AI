package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.WorkspaceChangeEntity;
import ai.codecraft.modules.workspace.model.entity.WorkspaceRestoreEntity;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkspaceHistoryRepository {
    String CHANGE = "id::text,exercise_id::text,workspace_instance_id::text,source,base_revision,result_revision,task_id::text,conversation_id::text,origin_change_id::text,retained,recorded_at";

    @Select("SELECT " + CHANGE + " FROM workspace_change WHERE id=#{id}::uuid")
    WorkspaceChangeEntity find(String id);

    @Select("SELECT " + CHANGE + " FROM workspace_change WHERE exercise_id=#{exercise}::uuid AND (#{before}=0 OR result_revision < #{before}) ORDER BY result_revision DESC LIMIT 21")
    List<WorkspaceChangeEntity> recent(@Param("exercise") String exercise, @Param("before") long before);

    @Insert("INSERT INTO workspace_change(id,exercise_id,workspace_instance_id,source,base_revision,result_revision,task_id,conversation_id,origin_change_id,retained,recorded_at) VALUES (#{id}::uuid,#{exerciseId}::uuid,#{workspaceInstanceId}::uuid,#{source},#{baseRevision},#{resultRevision},#{taskId}::uuid,#{conversationId}::uuid,#{originChangeId}::uuid,#{retained},#{recordedAt})")
    void insert(WorkspaceChangeEntity change);

    @Select("SELECT operation_id::text,exercise_id::text,change_id::text,mode,request_fingerprint,preview_fingerprint,expected_revision,target_revision FROM workspace_restore WHERE operation_id=#{id}::uuid")
    WorkspaceRestoreEntity restore(String id);

    @Insert("INSERT INTO workspace_restore(operation_id,exercise_id,change_id,mode,request_fingerprint,preview_fingerprint,expected_revision,target_revision) VALUES (#{operationId}::uuid,#{exerciseId}::uuid,#{changeId}::uuid,#{mode},#{requestFingerprint},#{previewFingerprint},#{expectedRevision},#{targetRevision})")
    void insertRestore(WorkspaceRestoreEntity restore);

    @Update("UPDATE workspace_change SET retained=false WHERE id IN (SELECT id FROM workspace_change WHERE exercise_id=#{exercise}::uuid AND retained ORDER BY result_revision DESC OFFSET 20)")
    int retireExcess(String exercise);

    @Update("<script>UPDATE workspace_change SET retained=false WHERE exercise_id=#{exercise}::uuid AND retained <if test='!protectedIds.isEmpty()'>AND id NOT IN <foreach item='id' collection='protectedIds' open='(' separator=',' close=')'>#{id}::uuid</foreach></if></script>")
    int retireForCapacity(@Param("exercise") String exercise, @Param("protectedIds") Set<String> protectedIds);

    @Select("SELECT COALESCE((SELECT sum(pg_column_size(c)::bigint) FROM workspace_change c WHERE exercise_id=#{exercise}::uuid),0) + COALESCE((SELECT sum(pg_column_size(r)::bigint) FROM workspace_restore r WHERE exercise_id=#{exercise}::uuid),0)")
    long retainedBytes(String exercise);

    @Delete("DELETE FROM workspace_restore WHERE exercise_id=#{exercise}::uuid")
    void eraseRestores(String exercise);

    @Delete("DELETE FROM workspace_change WHERE exercise_id=#{exercise}::uuid")
    void eraseChanges(String exercise);
}
