package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.dto.CopilotTestResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotTestRunEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CopilotTestRepository {
    String COLUMNS = "tool_id::text,task_id::text,exercise_id::text,workspace_instance_id::text,test_task_id::text,user_sequence,revision,target,state,result_state,result_version,result_payload,created_at,completed_at";
    @Select("SELECT " + COLUMNS + " FROM copilot_test_run WHERE tool_id=#{tool}::uuid")
    CopilotTestRunEntity forTool(String tool);
    @Select("SELECT " + COLUMNS + " FROM copilot_test_run WHERE task_id=#{task}::uuid AND state='WAITING' LIMIT 1")
    CopilotTestRunEntity pending(String task);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_test_run WHERE task_id=#{task}::uuid AND state='WAITING')")
    boolean waiting(String task);
    @Select("SELECT " + COLUMNS + " FROM copilot_test_run WHERE task_id=#{task}::uuid AND user_sequence=#{goal} AND revision=#{revision} AND target=#{target}")
    CopilotTestRunEntity previous(String task, long goal, long revision, String target);
    @Select("SELECT tool_id::text,task_id::text,test_task_id::text,revision::text,target,state,result_state,created_at,completed_at FROM copilot_test_run WHERE task_id=#{task}::uuid ORDER BY created_at DESC,tool_id DESC LIMIT 20")
    List<CopilotTestResponse> recent(String task);
    @Insert("""
        INSERT INTO copilot_test_run(tool_id,task_id,exercise_id,workspace_instance_id,test_task_id,user_sequence,revision,target,created_at)
        VALUES(#{toolId}::uuid,#{taskId}::uuid,#{exerciseId}::uuid,#{workspaceInstanceId}::uuid,#{testTaskId}::uuid,#{userSequence},#{revision},#{target},#{createdAt})
        """)
    void insert(CopilotTestRunEntity value);
    @Update("UPDATE copilot_test_run SET state='RESOLVED',result_state=#{state},result_version=#{version},result_payload=#{payload},completed_at=#{now} WHERE tool_id=#{tool}::uuid AND state='WAITING'")
    int resolved(String tool, String state, long version, String payload, Instant now);
}
