package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.dto.CopilotProposalResponse;
import ai.codecraft.modules.copilot.model.dto.CopilotProposalDecisionDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotPendingProposalDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotProposalEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CopilotProposalRepository {
    String COLUMNS = "id::text,sequence,task_id::text,tool_id::text,turn_id::text,exercise_id::text,user_id::text,workspace_instance_id::text,base_revision,state,mode,file_count,batch_payload,diff_payload,fingerprint,operation_id::text,decision_key,decision_fingerprint,decision_type,decision_actor::text,decision_reason,result_revision,changed,failure_code,created_at,decided_at,completed_at,mode_version";
    String IDENTITY = "id::text,task_id::text,user_id::text,tool_id::text,turn_id::text,exercise_id::text,workspace_instance_id::text,base_revision,state,operation_id::text,mode,mode_version";
    String SUMMARY = "id::text,sequence::text,task_id::text,state,mode,base_revision::text,file_count,result_revision::text,changed,decision_type,decision_reason,failure_code,created_at,decided_at,completed_at,mode_version::text";

    @Select("SELECT " + COLUMNS + " FROM copilot_proposal WHERE id=#{id}::uuid")
    CopilotProposalEntity find(String id);
    @Select("SELECT id::text,task_id::text,exercise_id::text,base_revision,state,batch_payload,decision_key,decision_fingerprint FROM copilot_proposal WHERE id=#{id}::uuid")
    CopilotProposalDecisionDTO decisionState(String id);
    @Select("SELECT " + IDENTITY + " FROM copilot_proposal WHERE id=#{id}::uuid")
    CopilotPendingProposalDTO identity(String id);
    @Select("SELECT " + IDENTITY + " FROM copilot_proposal WHERE task_id=#{task}::uuid AND state IN ('PENDING','APPLYING') LIMIT 1")
    CopilotPendingProposalDTO pending(String task);
    @Select("SELECT p.task_id::text FROM copilot_proposal p JOIN copilot_task t ON t.id=p.task_id WHERE p.mode='AUTO' AND p.state='PENDING' AND t.state='WAITING_APPROVAL' AND t.worker_id IS NULL AND NOT t.stop_requested ORDER BY p.created_at,p.id LIMIT 32")
    List<String> automaticPending();
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_proposal WHERE task_id=#{task}::uuid AND state='REJECTED' AND fingerprint=#{fingerprint})")
    boolean rejected(String task, String fingerprint);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_proposal WHERE task_id=#{task}::uuid AND state='APPLYING')")
    boolean applying(String task);
    @Select("SELECT " + SUMMARY + " FROM copilot_proposal WHERE id=#{id}::uuid")
    CopilotProposalResponse summary(String id);
    @Select("SELECT " + SUMMARY + " FROM copilot_proposal WHERE task_id=#{task}::uuid AND (#{before}=0 OR sequence<#{before}) ORDER BY copilot_proposal.sequence DESC LIMIT 21")
    List<CopilotProposalResponse> page(String task, long before);
    @Insert("""
        INSERT INTO copilot_proposal(id,task_id,tool_id,turn_id,exercise_id,user_id,workspace_instance_id,base_revision,
            state,mode,file_count,batch_payload,diff_payload,fingerprint,created_at,mode_version)
        VALUES(#{id}::uuid,#{taskId}::uuid,#{toolId}::uuid,#{turnId}::uuid,#{exerciseId}::uuid,#{userId}::uuid,#{workspaceInstanceId}::uuid,
            #{baseRevision},'PENDING',#{mode},#{fileCount},#{batchPayload},#{diffPayload},#{fingerprint},#{createdAt},#{modeVersion})
        """)
    void insert(CopilotProposalEntity proposal);
    @Update("UPDATE copilot_proposal SET decision_key=#{key},decision_fingerprint=#{fingerprint},decision_type=#{type},decision_actor=#{actor}::uuid,decision_reason=#{reason},decided_at=#{now} WHERE id=#{id}::uuid AND state='PENDING' AND decision_key IS NULL")
    int decision(String id, String key, String fingerprint, String type, String actor, String reason, Instant now);
    @Update("UPDATE copilot_proposal SET state='APPLYING',operation_id=#{operation}::uuid WHERE id=#{id}::uuid AND state='PENDING'")
    int applyingOperation(String id, String operation);
    @Update("UPDATE copilot_proposal SET state=#{state},result_revision=#{revision},changed=#{changed},failure_code=#{failure},completed_at=#{now} WHERE id=#{id}::uuid AND state IN ('PENDING','APPLYING')")
    int finish(String id, String state, Long revision, Boolean changed, String failure, Instant now);
    @Update("UPDATE copilot_proposal SET state='CANCELLED',failure_code=#{reason},completed_at=#{now} WHERE task_id=#{task}::uuid AND state='PENDING'")
    void cancelPending(String task, String reason, Instant now);
}
