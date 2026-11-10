package ai.codecraft.modules.workspace.repository;

import ai.codecraft.modules.workspace.model.entity.TaskEventEntity;
import ai.codecraft.modules.workspace.model.entity.TaskStreamEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskStreamRepository {
    @Insert("INSERT INTO workspace_task_stream(task_id, stream_id) VALUES (#{task}::uuid, #{stream}::uuid)")
    void create(@Param("task") String task, @Param("stream") String stream);
    @Select("SELECT task_id::text, stream_id::text, sequence, first_sequence FROM workspace_task_stream WHERE task_id = #{task}::uuid")
    TaskStreamEntity find(String task);
    @Insert("INSERT INTO workspace_task_event(task_id, sequence, type, payload, bytes) VALUES (#{task}::uuid, #{sequence}, #{type}, #{payload}, #{bytes})")
    void append(@Param("task") String task, @Param("sequence") long sequence, @Param("type") String type, @Param("payload") String payload, @Param("bytes") int bytes);
    @Update("UPDATE workspace_task_stream SET sequence = #{sequence} WHERE task_id = #{task}::uuid")
    void advance(@Param("task") String task, @Param("sequence") long sequence);
    @Delete("DELETE FROM workspace_task_event WHERE task_id = #{task}::uuid AND sequence IN (SELECT sequence FROM (SELECT sequence, row_number() OVER (ORDER BY sequence DESC) AS position, sum(bytes) OVER (ORDER BY sequence DESC) AS total FROM workspace_task_event WHERE task_id = #{task}::uuid) retained_window WHERE position > 4096 OR total > 4194304)")
    void trim(String task);
    @Update("UPDATE workspace_task_stream SET first_sequence = COALESCE((SELECT min(sequence) FROM workspace_task_event WHERE task_id = #{task}::uuid), sequence + 1) WHERE task_id = #{task}::uuid")
    void retained(String task);
    @Select("SELECT sequence, type, payload, bytes FROM (SELECT sequence, type, payload, bytes, sum(bytes + 256) OVER (ORDER BY sequence) AS total FROM workspace_task_event WHERE task_id = #{task}::uuid AND sequence > #{after} ORDER BY sequence LIMIT 256) batch WHERE total <= 65536 ORDER BY sequence")
    List<TaskEventEntity> batch(@Param("task") String task, @Param("after") long after);
}
