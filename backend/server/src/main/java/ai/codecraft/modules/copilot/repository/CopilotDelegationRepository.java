package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.entity.CopilotDelegationEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CopilotDelegationRepository {
    String FIELDS = "tool_id::text,exercise_id::text,parent_task_id::text,child_task_id::text,conversation_id::text,goal,state,result_payload,result_message_id::text,created_at,completed_at";

    @Select("SELECT " + CopilotTaskRepository.MESSAGE + " FROM copilot_message WHERE task_id=#{task}::uuid AND kind=#{kind} ORDER BY sequence DESC LIMIT 1")
    CopilotMessageEntity lastMessage(String task, String kind);

    @Select("SELECT " + FIELDS + " FROM copilot_delegation WHERE tool_id=#{tool}::uuid")
    CopilotDelegationEntity find(String tool);
    @Select("SELECT " + FIELDS + " FROM copilot_delegation WHERE parent_task_id=#{parent}::uuid AND state='WAITING'")
    CopilotDelegationEntity pending(String parent);
    @Select("SELECT " + FIELDS + " FROM copilot_delegation WHERE parent_task_id=#{parent}::uuid ORDER BY created_at,tool_id")
    List<CopilotDelegationEntity> list(String parent);
    @Select("SELECT " + CopilotTaskRepository.TASK + " FROM copilot_task WHERE id IN (SELECT child_task_id FROM copilot_delegation WHERE parent_task_id=#{parent}::uuid)")
    List<CopilotTaskEntity> children(String parent);
    @Select("SELECT count(*) FROM copilot_delegation WHERE parent_task_id=#{parent}::uuid")
    int count(String parent);
    @Insert("INSERT INTO copilot_delegation(tool_id,exercise_id,parent_task_id,child_task_id,conversation_id,goal,state,created_at) VALUES(#{toolId}::uuid,#{exerciseId}::uuid,#{parentTaskId}::uuid,#{childTaskId}::uuid,#{conversationId}::uuid,#{goal},'WAITING',#{createdAt})")
    void insert(CopilotDelegationEntity delegation);
    @Update("UPDATE copilot_delegation SET state='RESOLVED',result_payload=#{payload},completed_at=#{now} WHERE tool_id=#{tool}::uuid AND state='WAITING'")
    int resolve(String tool, String payload, Instant now);
    @Update("UPDATE copilot_delegation SET result_message_id=#{message}::uuid WHERE tool_id=#{tool}::uuid AND state='RESOLVED'")
    void resultMessage(String tool, String message);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_delegation d JOIN copilot_task p ON p.id=d.parent_task_id WHERE d.child_task_id=#{child}::uuid AND d.state='RESOLVED' AND (p.conversation_id=#{conversation}::uuid OR EXISTS(SELECT 1 FROM copilot_conversation_message m WHERE m.conversation_id=#{conversation}::uuid AND m.id=d.result_message_id)))")
    boolean evidenceVisible(String conversation, String child);
}
