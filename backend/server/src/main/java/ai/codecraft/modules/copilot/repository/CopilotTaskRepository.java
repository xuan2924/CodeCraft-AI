package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import ai.codecraft.modules.copilot.model.dto.CopilotPartialResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO;
import ai.codecraft.modules.copilot.model.entity.CopilotTaskEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotTurnEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotToolEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CopilotTaskRepository {
    String TASK = "id::text,exercise_id::text,user_id::text,session_hash,request_key,fingerprint,state,reason,stop_requested,worker_id::text,worker_not_after,created_at,completed_at,conversation_id::text,lane";
    String TURN = "id::text,task_id::text,exercise_id::text,ordinal,state,connection_id::text,model_configuration_id::text,connection_configuration_version,model_configuration_version,protocol,model_id,context_window,assistant_message_id::text,tool_position,created_at,not_after,completed_at,error_code";
    String MESSAGE = "sequence,id::text,task_id::text,exercise_id::text,kind,payload,created_at";

    @Update("UPDATE copilot_turn SET price_snapshot=#{snapshot} WHERE id=#{id}::uuid AND state='DISPATCHED'")
    void recordPrice(String id, String snapshot);
    @Select("SELECT price_snapshot FROM copilot_turn WHERE id=#{id}::uuid")
    String priceSnapshot(String id);
    @Update("UPDATE copilot_turn SET usage_facts=#{receipt.factsPayload}::jsonb,estimated_cost=#{receipt.amount},cost_currency=#{receipt.currency},price_reason=#{receipt.priceReason} WHERE id=#{id}::uuid")
    void accounting(String id, ModelUsageReceiptDTO receipt);

    @Select("SELECT " + TASK + " FROM copilot_task WHERE id=#{id}::uuid")
    CopilotTaskEntity find(String id);
    @Select("SELECT " + TASK + " FROM copilot_task WHERE exercise_id=#{exercise}::uuid AND request_key=#{key}")
    CopilotTaskEntity receipt(String exercise, String key);
    @Select("SELECT " + TASK + " FROM copilot_task WHERE conversation_id=#{conversation}::uuid ORDER BY admission_sequence DESC LIMIT 1")
    CopilotTaskEntity latest(String conversation);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_task WHERE exercise_id=#{exercise}::uuid AND lane='MAIN' AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING'))")
    boolean active(String exercise);
    @Select("SELECT " + TASK + " FROM copilot_task WHERE exercise_id=#{exercise}::uuid AND lane='MAIN' AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING')")
    CopilotTaskEntity activeTask(String exercise);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_task WHERE exercise_id=#{exercise}::uuid AND lane=#{lane} AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING'))")
    boolean activeLane(String exercise, String lane);
    @Select("SELECT " + TASK + " FROM copilot_task WHERE conversation_id=#{conversation}::uuid AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING')")
    CopilotTaskEntity activeInConversation(String conversation);
    @Insert("""
        INSERT INTO copilot_task(id,exercise_id,user_id,session_hash,request_key,fingerprint,state,created_at,conversation_id,lane)
        VALUES(#{id}::uuid,#{exerciseId}::uuid,#{userId}::uuid,#{sessionHash},#{requestKey},#{fingerprint},'QUEUED',#{createdAt},#{conversationId}::uuid,#{lane})
        """)
    void insert(CopilotTaskEntity task);
    @Update("UPDATE copilot_task SET no_progress_count=CASE WHEN last_progress_signature=#{signature} THEN no_progress_count+1 ELSE 0 END,last_progress_signature=#{signature} WHERE id=#{id}::uuid")
    void progress(String id, String signature);
    @Update("UPDATE copilot_task SET no_progress_count=0,last_progress_signature=NULL WHERE id=#{id}::uuid")
    void resetProgress(String id);
    @Select("SELECT no_progress_count FROM copilot_task WHERE id=#{id}::uuid")
    int noProgress(String id);
    @Select("SELECT id::text FROM copilot_task WHERE state='STOPPING' ORDER BY created_at,id LIMIT 100")
    List<String> stopping();
    @Select("SELECT id::text FROM copilot_task WHERE state='QUEUED' ORDER BY created_at,id LIMIT 100")
    List<String> queued();
    @Update("UPDATE copilot_task SET state='RUNNING',worker_id=#{worker}::uuid,worker_not_after=#{until} WHERE id=#{id}::uuid AND state='QUEUED' AND NOT stop_requested")
    int claim(String id, String worker, Instant until);
    @Update("UPDATE copilot_task SET worker_not_after=#{until} WHERE id=#{id}::uuid AND worker_id=#{worker}::uuid AND state IN ('RUNNING','STOPPING')")
    int renew(String id, String worker, Instant until);
    @Update("UPDATE copilot_task SET stop_requested=true,reason='USER_STOP',state=CASE WHEN worker_id IS NULL AND NOT EXISTS(SELECT 1 FROM copilot_turn WHERE task_id=#{id}::uuid AND state IN ('DISPATCHED','TOOLS')) THEN 'STOPPED' ELSE 'STOPPING' END,completed_at=CASE WHEN worker_id IS NULL AND NOT EXISTS(SELECT 1 FROM copilot_turn WHERE task_id=#{id}::uuid AND state IN ('DISPATCHED','TOOLS')) THEN #{now}::timestamptz ELSE NULL END WHERE id=#{id}::uuid AND state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING')")
    void stop(String id, Instant now);
    @Update("UPDATE copilot_task SET state=#{state},reason=#{reason},completed_at=#{completed},worker_id=NULL,worker_not_after=NULL WHERE id=#{id}::uuid AND worker_id=#{worker}::uuid")
    int finish(String id, String worker, String state, String reason, Instant completed);
    @Update("UPDATE copilot_task SET state=#{state},reason=#{reason},worker_id=NULL,worker_not_after=NULL WHERE id=#{id}::uuid AND worker_id=#{worker}::uuid AND NOT stop_requested")
    int park(String id, String worker, String state, String reason);
    @Update("UPDATE copilot_task SET state=#{state},reason=#{reason} WHERE id=#{id}::uuid AND worker_id IS NULL AND state IN ('WAITING_APPROVAL','WAITING_WRITE') AND NOT stop_requested")
    int waitFor(String id, String state, String reason);
    @Update("UPDATE copilot_task SET state='QUEUED',reason=NULL WHERE id=#{id}::uuid AND worker_id IS NULL AND state IN ('WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD') AND NOT stop_requested")
    int wake(String id);
    @Update("UPDATE copilot_task SET state='STOPPING',stop_requested=true,reason=#{reason} WHERE id=#{id}::uuid AND worker_id IS NULL AND state IN ('QUEUED','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD')")
    void haltIdle(String id, String reason);
    @Update("UPDATE copilot_task SET state=#{state},reason=#{reason},completed_at=#{now} WHERE id=#{id}::uuid AND worker_id IS NULL AND state IN ('QUEUED','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING')")
    int finishIdle(String id, String state, String reason, Instant now);
    @Select("SELECT id::text FROM copilot_task WHERE worker_id IS NULL AND state IN ('QUEUED','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING') ORDER BY last_checked_at,id LIMIT 100")
    List<String> idle();
    @Update("UPDATE copilot_task SET last_checked_at=#{now} WHERE id=#{id}::uuid")
    void checked(String id, Instant now);
    @Update("UPDATE copilot_task SET state='INTERRUPTED',reason=#{reason},completed_at=#{now} WHERE id=#{id}::uuid AND state IN ('QUEUED','PAUSED')")
    int expireIdle(String id, String reason, Instant now);
    @Update("UPDATE copilot_task SET session_hash=#{session} WHERE id=#{id}::uuid AND state='WAITING_APPROVAL' AND worker_id IS NULL AND NOT stop_requested")
    int reauthorizeWaiting(String id, String session);
    @Update("UPDATE copilot_task SET state='QUEUED',reason=NULL,session_hash=#{session},worker_id=NULL,worker_not_after=NULL WHERE id=#{id}::uuid AND state='PAUSED' AND NOT stop_requested")
    int resume(String id, String session);
    @Select("SELECT id::text FROM copilot_task WHERE state IN ('RUNNING','STOPPING') AND worker_not_after<=#{now} ORDER BY worker_not_after,id LIMIT 100")
    List<String> expired(Instant now);

    @Insert("INSERT INTO copilot_message(id,task_id,exercise_id,kind,payload,created_at) VALUES(#{id}::uuid,#{taskId}::uuid,#{exerciseId}::uuid,#{kind},#{payload},#{createdAt})")
    void message(CopilotMessageEntity message);
    @Select("SELECT progress_signature FROM copilot_turn WHERE id=#{turn}::uuid")
    String batchSignature(String turn);
    @Update("UPDATE copilot_turn SET progress_recorded=true WHERE id=#{turn}::uuid AND state='SUCCEEDED' AND NOT progress_recorded")
    int recordProgress(String turn);
    @Update("UPDATE copilot_turn SET progress_signature=#{signature} WHERE id=#{turn}::uuid AND state IN ('TOOLS','SUCCEEDED')")
    void batchProgress(String turn, String signature);
    @Select("SELECT " + MESSAGE + " FROM copilot_message WHERE id=#{id}::uuid")
    CopilotMessageEntity messageById(String id);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_conversation_message WHERE conversation_id=#{conversation}::uuid AND id=#{id}::uuid)")
    boolean hasMessage(String conversation, String id);
    @Select("WITH candidates AS (SELECT " + MESSAGE + " FROM copilot_conversation_message WHERE conversation_id=#{conversation}::uuid AND sequence>#{after} ORDER BY sequence LIMIT 100), budgeted AS (SELECT *,sum(octet_length(payload)) OVER (ORDER BY sequence) AS bytes FROM candidates) SELECT " + MESSAGE + " FROM budgeted WHERE bytes<=524288 ORDER BY sequence")
    List<CopilotMessageEntity> page(String conversation, long after);
    @Select("WITH candidates AS (SELECT " + MESSAGE + " FROM copilot_conversation_message WHERE conversation_id=#{conversation}::uuid ORDER BY sequence DESC LIMIT 100), budgeted AS (SELECT *,sum(octet_length(payload)) OVER (ORDER BY sequence DESC) AS bytes FROM candidates) SELECT " + MESSAGE + " FROM budgeted WHERE bytes<=524288 ORDER BY sequence")
    List<CopilotMessageEntity> recent(String conversation);
    @Select("SELECT coalesce(max(sequence),0) FROM copilot_conversation_message WHERE conversation_id=#{conversation}::uuid")
    long lastSequence(String conversation);
    @Select("SELECT coalesce(max(sequence),0) FROM copilot_message WHERE task_id=#{task}::uuid AND kind='USER'")
    long goalSequence(String task);
    @Select("SELECT id::text AS turn_id,partial_sequence::text AS sequence,left(partial_text,4096) AS text,length(partial_text)>4096 AS truncated,left(partial_thinking,4096) AS thinking,length(partial_thinking)>4096 AS thinking_truncated FROM copilot_turn WHERE task_id=#{task}::uuid ORDER BY ordinal DESC LIMIT 1")
    CopilotPartialResponse partial(String task);
    @Update("UPDATE copilot_turn SET partial_text=#{text},partial_thinking=#{thinking},partial_sequence=partial_sequence+1 WHERE id=#{turn}::uuid AND state='DISPATCHED'")
    int partialText(String turn, String text, String thinking);
    @Select("SELECT coalesce((SELECT text_history FROM copilot_turn WHERE task_id=#{task}::uuid ORDER BY ordinal DESC LIMIT 1),false)")
    boolean textHistory(String task);
    @Select("SELECT coalesce(sum(octet_length(payload)),0)::bigint FROM copilot_conversation_message WHERE conversation_id=#{conversation}::uuid")
    long contextBytes(String conversation);
    @Select("SELECT " + MESSAGE + " FROM copilot_conversation_message WHERE conversation_id=#{conversation}::uuid ORDER BY sequence")
    List<CopilotMessageEntity> context(String conversation);

    @Insert("INSERT INTO copilot_tool(id,turn_id,task_id,exercise_id,position,call_id,name,arguments,state,created_at) VALUES(#{id}::uuid,#{turnId}::uuid,#{taskId}::uuid,#{exerciseId}::uuid,#{position},#{callId},#{name},#{arguments},'RUNNING',#{createdAt})")
    void tool(CopilotToolEntity tool);
    @Select("SELECT id::text,turn_id::text,task_id::text,exercise_id::text,position,call_id,name,arguments,state,result_message_id::text,created_at,completed_at FROM copilot_tool WHERE turn_id=#{turn}::uuid AND position=#{position}")
    CopilotToolEntity toolAt(String turn, int position);
    @Select("SELECT id::text,turn_id::text,task_id::text,exercise_id::text,position,call_id,name,arguments,state,result_message_id::text,created_at,completed_at FROM copilot_tool WHERE id=#{id}::uuid")
    CopilotToolEntity toolById(String id);
    @Update("UPDATE copilot_tool SET state=#{state} WHERE id=#{id}::uuid AND state IN ('RUNNING','WAITING_APPROVAL')")
    int waitTool(String id, String state);
    @Update("UPDATE copilot_tool SET state=#{state},result_message_id=#{message}::uuid,completed_at=#{now} WHERE id=#{id}::uuid AND state IN ('RUNNING','WAITING_APPROVAL','APPLYING','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD')")
    int completeTool(String id, String state, String message, Instant now);

    @Select("SELECT EXISTS(SELECT 1 FROM copilot_turn WHERE assistant_message_id IN (SELECT id FROM copilot_conversation_message WHERE conversation_id=#{conversation}::uuid) AND (connection_id<>#{connection}::uuid OR model_configuration_id<>#{model}::uuid OR connection_configuration_version<>#{connectionVersion} OR model_configuration_version<>#{modelVersion}))")
    boolean foreignHistory(String conversation, String connection, String model, long connectionVersion, long modelVersion);
    @Update("UPDATE copilot_turn SET text_history=#{converted} WHERE id=#{id}::uuid")
    void historyMode(String id, boolean converted);
    @Select("SELECT " + TURN + " FROM copilot_turn WHERE task_id=#{task}::uuid ORDER BY ordinal DESC LIMIT 1")
    CopilotTurnEntity latestTurn(String task);
    @Insert("""
        INSERT INTO copilot_turn(id,task_id,exercise_id,ordinal,state,connection_id,model_configuration_id,
          connection_configuration_version,model_configuration_version,protocol,model_id,context_window,created_at,not_after)
        VALUES(#{id}::uuid,#{taskId}::uuid,#{exerciseId}::uuid,#{ordinal},'DISPATCHED',#{connectionId}::uuid,#{modelConfigurationId}::uuid,
          #{connectionConfigurationVersion},#{modelConfigurationVersion},#{protocol},#{modelId},#{contextWindow},#{createdAt},#{notAfter})
        """)
    void turn(CopilotTurnEntity turn);
    @Select("SELECT tool.arguments FROM copilot_tool tool JOIN copilot_turn turn ON turn.id=tool.turn_id WHERE tool.task_id=#{task}::uuid AND tool.name='view_file' AND tool.state='SUCCEEDED' ORDER BY turn.ordinal DESC,tool.position DESC LIMIT 32")
    List<String> instructionPaths(String task);
    @Update("UPDATE copilot_turn SET project_instructions=#{payload} WHERE id=#{turn}::uuid")
    void recordProjectInstructions(String turn, String payload);
    @Select("SELECT project_instructions FROM copilot_turn WHERE task_id=#{task}::uuid ORDER BY ordinal DESC LIMIT 1")
    String projectInstructions(String task);

    @Update("UPDATE copilot_turn SET configuration_payload=#{payload} WHERE id=#{turn}::uuid")
    void configuration(String turn, String payload);
    @Update("UPDATE copilot_turn SET state=#{state},partial_text='',partial_thinking='',partial_sequence=partial_sequence+1,assistant_message_id=#{message}::uuid,input_tokens=#{input},output_tokens=#{output},total_tokens=#{total},usage_incomplete=#{incomplete},usage_payload=#{usagePayload},completed_at=#{now} WHERE id=#{id}::uuid AND state='DISPATCHED'")
    int response(String id, String state, String message, Long input, Long output, Long total, boolean incomplete, String usagePayload, Instant now);
    @Update("UPDATE copilot_turn SET tool_position=tool_position+1,state=CASE WHEN #{last} THEN 'SUCCEEDED' ELSE 'TOOLS' END WHERE id=#{id}::uuid AND state='TOOLS' AND tool_position=#{expected}")
    int advanceTool(String id, int expected, boolean last);
    @Update("UPDATE copilot_turn SET state=#{state},error_code=#{reason},completed_at=#{now} WHERE id=#{id}::uuid AND state IN ('DISPATCHED','TOOLS')")
    void failTurn(String id, String state, String reason, Instant now);
}
