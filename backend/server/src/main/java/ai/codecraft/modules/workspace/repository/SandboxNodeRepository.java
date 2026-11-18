package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.dto.SandboxNodeDTO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SandboxNodeRepository {
    String COLUMNS = "n.id,n.endpoint,n.storage_id,n.host_epoch,n.active_slots,n.max_slots,n.healthy_until,";
    @Insert("INSERT INTO sandbox_node(id,endpoint) VALUES(#{id},#{endpoint}) ON CONFLICT(id) DO NOTHING")
    void register(String id, String endpoint);
    @Select("SELECT " + COLUMNS + "0 AS reservations,n.ready FROM sandbox_node n WHERE n.id=#{id}")
    SandboxNodeDTO node(String id);
    @Update("""
        UPDATE sandbox_node SET storage_id=coalesce(storage_id,#{storage}),host_epoch=#{epoch},active_slots=#{active},max_slots=#{maximum},ready=#{ready},
          loaded_packages=string_to_array(#{packages},','),healthy_until=clock_timestamp()+interval '15 seconds',updated_at=clock_timestamp()
        WHERE id=#{id} AND endpoint=#{endpoint} AND (storage_id IS NULL OR storage_id=#{storage})
        """)
    int healthy(String id, String endpoint, String storage, String epoch, int active, int maximum, boolean ready, String packages);
    @Update("UPDATE sandbox_node SET healthy_until='-infinity',updated_at=clock_timestamp() WHERE id=#{id}")
    void unhealthy(String id);
    @Select("""
        SELECT n.id,n.endpoint,n.storage_id,n.host_epoch,n.active_slots,n.max_slots,n.healthy_until,
          (SELECT count(*) FROM sandbox_binding b JOIN workspace_exercise e ON e.id=b.exercise_id WHERE b.node_id=n.id AND e.state='PREPARING') AS reservations,n.ready
        FROM sandbox_node n WHERE #{digest}=ANY(n.loaded_packages) ORDER BY n.id FOR UPDATE OF n
        """)
    List<SandboxNodeDTO> candidates(String digest);
    @Select("SELECT node_id FROM sandbox_binding WHERE exercise_id=#{exercise}::uuid")
    String bound(String exercise);
    @Insert("INSERT INTO sandbox_binding(exercise_id,node_id) VALUES(#{exercise}::uuid,#{node})")
    void bind(String exercise, String node);
    @Select("""
        SELECT b.node_id FROM workspace_storage_instance i JOIN sandbox_binding b ON b.exercise_id=i.exercise_id
        WHERE i.workspace_instance_id=#{instance}::uuid
        """)
    String instance(String instance);
    @Select("SELECT count(*) FROM (" + TestTaskRepository.OCCUPANTS + ") occupants JOIN sandbox_binding b ON b.exercise_id=occupants.exercise_id WHERE b.node_id=#{node}")
    int occupied(String node);
}
