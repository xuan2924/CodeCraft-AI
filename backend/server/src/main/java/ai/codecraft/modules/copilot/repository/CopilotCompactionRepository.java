package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.entity.CopilotCompactionCallEntity;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionReceiptDTO;
import ai.codecraft.modules.copilot.model.dto.CopilotCompactionResponse;
import ai.codecraft.modules.copilot.model.entity.CopilotCompactionEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotContextEntity;
import ai.codecraft.modules.copilot.model.entity.CopilotMessageEntity;
import java.time.Instant;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CopilotCompactionRepository {
    @Update("UPDATE copilot_compaction_call SET price_snapshot=#{snapshot} WHERE id=#{id}::uuid AND state='DISPATCHED'")
    void recordPrice(String id, String snapshot);
    @Select("SELECT price_snapshot FROM copilot_compaction_call WHERE id=#{id}::uuid")
    String priceSnapshot(String id);
    @Update("UPDATE copilot_compaction_call SET usage_facts=#{receipt.factsPayload}::jsonb,estimated_cost=#{receipt.amount},cost_currency=#{receipt.currency},price_reason=#{receipt.priceReason} WHERE id=#{id}::uuid")
    void accounting(String id, ModelUsageReceiptDTO receipt);
    String JOB = "id::text,sequence,exercise_id::text,user_id::text,session_hash,actor_auth_version,anchor_task_id::text,bound_task_id::text,request_key,fingerprint,trigger_kind,state,stop_requested,source_version,source_summary_id::text,through_sequence,source_ids_payload,summary_payload,failure_code,attempt_count,worker_id::text,worker_task_id::text,worker_not_after,created_at,completed_at,conversation_id::text";
    String CALL = "id::text,compaction_id::text,exercise_id::text,ordinal,state,connection_id::text,model_configuration_id::text,connection_configuration_version,model_configuration_version,protocol,model_id,context_window,output_limit,source_version,source_summary_id::text,through_sequence,source_ids_payload,estimated_input_tokens,configuration_payload,response_payload,input_tokens,output_tokens,total_tokens,usage_incomplete,usage_payload,error_code,created_at,not_after,completed_at";

    @Select("SELECT exercise_id::text,version,summary_id::text,through_sequence,updated_at FROM copilot_context WHERE conversation_id=#{conversation}::uuid")
    CopilotContextEntity head(String conversation);
    @Insert("INSERT INTO copilot_context(exercise_id,conversation_id,updated_at) VALUES(coalesce((SELECT exercise_id FROM copilot_conversation WHERE id=#{conversation}::uuid),#{conversation}::uuid),#{conversation}::uuid,#{now}) ON CONFLICT(conversation_id) DO NOTHING")
    void ensureHead(String conversation, Instant now);
    @Update("UPDATE copilot_context SET version=version+1,summary_id=#{summary}::uuid,through_sequence=#{through},updated_at=#{now} WHERE conversation_id=#{conversation}::uuid AND version=#{expected} AND through_sequence<=#{through}")
    int publish(String conversation, long expected, String summary, long through, Instant now);

    @Select("SELECT " + JOB + " FROM copilot_compaction WHERE id=#{id}::uuid")
    CopilotCompactionEntity find(String id);
    @Select("SELECT " + JOB + " FROM copilot_compaction WHERE exercise_id=#{exercise}::uuid AND request_key=#{key}")
    CopilotCompactionEntity receipt(String exercise, String key);
    @Select("SELECT " + JOB + " FROM copilot_compaction WHERE exercise_id=#{exercise}::uuid AND state IN ('WAITING','RUNNING')")
    CopilotCompactionEntity pending(String exercise);
    @Select("SELECT " + JOB + " FROM copilot_compaction WHERE conversation_id=#{conversation}::uuid AND state IN ('WAITING','RUNNING')")
    CopilotCompactionEntity pendingConversation(String conversation);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_compaction WHERE exercise_id=#{exercise}::uuid AND state IN ('WAITING','RUNNING'))")
    boolean active(String exercise);
    @Select("SELECT id::text,sequence::text,exercise_id::text,trigger_kind AS trigger,state,failure_code AS reason,attempt_count AS attempts,stop_requested,source_version::text,through_sequence::text,created_at,completed_at FROM copilot_compaction c WHERE conversation_id=#{conversation}::uuid ORDER BY c.sequence DESC LIMIT 20")
    List<CopilotCompactionResponse> recent(String conversation);
    @Select("SELECT summary_payload FROM copilot_compaction WHERE id=#{id}::uuid AND conversation_id=#{conversation}::uuid AND state='APPLIED'")
    String summary(String id, String conversation);
    @Insert("""
        INSERT INTO copilot_compaction(id,exercise_id,user_id,session_hash,actor_auth_version,anchor_task_id,bound_task_id,request_key,fingerprint,trigger_kind,state,created_at,conversation_id)
        VALUES(#{id}::uuid,#{exerciseId}::uuid,#{userId}::uuid,#{sessionHash},#{actorAuthVersion},#{anchorTaskId}::uuid,#{boundTaskId}::uuid,#{requestKey},#{fingerprint},#{triggerKind},'WAITING',#{createdAt},#{conversationId}::uuid)
        """)
    void insert(CopilotCompactionEntity job);
    @Update("UPDATE copilot_compaction SET state='RUNNING',worker_id=#{worker}::uuid,worker_task_id=#{task}::uuid,worker_not_after=#{until},last_checked_at=#{now} WHERE id=#{id}::uuid AND state='WAITING' AND NOT stop_requested")
    int claim(String id, String worker, String task, Instant until, Instant now);
    @Update("UPDATE copilot_compaction SET attempt_count=attempt_count+1,source_version=#{version},source_summary_id=#{summary}::uuid,through_sequence=#{through},source_ids_payload=#{ids},worker_not_after=#{until} WHERE id=#{id}::uuid AND state='RUNNING' AND worker_id=#{worker}::uuid AND NOT stop_requested AND attempt_count<2")
    int attempt(String id, String worker, long version, String summary, long through, String ids, Instant until);
    @Update("UPDATE copilot_compaction SET state=#{state},summary_payload=#{summary},failure_code=#{reason},completed_at=#{now},worker_id=NULL,worker_not_after=NULL WHERE id=#{id}::uuid AND state IN ('WAITING','RUNNING')")
    int finish(String id, String state, String summary, String reason, Instant now);
    @Update("UPDATE copilot_compaction SET stop_requested=true,failure_code=coalesce(failure_code,#{reason}) WHERE id=#{id}::uuid AND state IN ('WAITING','RUNNING')")
    void stop(String id, String reason);
    @Update("UPDATE copilot_compaction SET last_checked_at=#{now} WHERE id=#{id}::uuid")
    void checked(String id, Instant now);
    @Select("SELECT id::text FROM copilot_compaction WHERE state IN ('WAITING','RUNNING') ORDER BY last_checked_at,sequence LIMIT 100")
    List<String> discover();

    @Select("SELECT c.id::text FROM copilot_compaction c WHERE c.state='WAITING' AND NOT c.stop_requested AND NOT EXISTS(SELECT 1 FROM copilot_task t WHERE t.exercise_id=c.exercise_id AND t.state IN ('QUEUED','RUNNING','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','STOPPING')) ORDER BY c.last_checked_at,c.sequence LIMIT 100")
    List<String> runnableStandalone();

    @Select("SELECT EXISTS(SELECT 1 FROM copilot_compaction WHERE worker_task_id=#{task}::uuid AND state='INTERRUPTED' AND failure_code='MODEL_RESULT_UNKNOWN')")
    boolean interruptedInline(String task);

    @Select("SELECT " + CALL + " FROM copilot_compaction_call WHERE compaction_id=#{job}::uuid ORDER BY ordinal DESC LIMIT 1")
    CopilotCompactionCallEntity latestCall(String job);
    @Insert("""
        INSERT INTO copilot_compaction_call(id,compaction_id,exercise_id,ordinal,state,connection_id,model_configuration_id,
          connection_configuration_version,model_configuration_version,protocol,model_id,context_window,output_limit,
          source_version,source_summary_id,through_sequence,source_ids_payload,estimated_input_tokens,configuration_payload,created_at,not_after)
        VALUES(#{id}::uuid,#{compactionId}::uuid,#{exerciseId}::uuid,#{ordinal},'DISPATCHED',#{connectionId}::uuid,#{modelConfigurationId}::uuid,
          #{connectionConfigurationVersion},#{modelConfigurationVersion},#{protocol},#{modelId},#{contextWindow},#{outputLimit},
          #{sourceVersion},#{sourceSummaryId}::uuid,#{throughSequence},#{sourceIdsPayload},#{estimatedInputTokens},#{configurationPayload},#{createdAt},#{notAfter})
        """)
    void call(CopilotCompactionCallEntity call);
    @Update("""
        UPDATE copilot_compaction_call SET state=#{state},response_payload=#{receipt.responsePayload},input_tokens=#{receipt.inputTokens},output_tokens=#{receipt.outputTokens},
          total_tokens=#{receipt.totalTokens},usage_incomplete=#{receipt.usageIncomplete},usage_payload=#{receipt.usagePayload},error_code=#{receipt.failureCode},completed_at=#{now}
        WHERE id=#{id}::uuid AND state='DISPATCHED'
        """)
    int result(String id, String state, CopilotCompactionReceiptDTO receipt, Instant now);
    @Update("UPDATE copilot_compaction_call SET response_payload=#{payload} WHERE id=#{id}::uuid AND state='DISPATCHED'")
    void partial(String id, String payload);
    @Update("UPDATE copilot_compaction_call SET state='INTERRUPTED',error_code=#{reason},completed_at=#{now} WHERE id=#{id}::uuid AND state='DISPATCHED'")
    int interruptCall(String id, String reason, Instant now);
    @Select("SELECT EXISTS(SELECT 1 FROM copilot_compaction_call k JOIN copilot_compaction c ON c.id=k.compaction_id WHERE c.conversation_id=#{conversation}::uuid AND k.source_version=#{version} AND k.through_sequence>=#{through} AND c.state IN ('FAILED','INTERRUPTED'))")
    boolean failedSource(String conversation, long version, long through);

    @Select("WITH candidates AS (SELECT " + CopilotTaskRepository.MESSAGE + " FROM copilot_message WHERE task_id IN (SELECT id FROM copilot_task WHERE conversation_id=#{conversation}::uuid) AND sequence>#{after} AND sequence<=#{through} ORDER BY sequence LIMIT #{limit}), budgeted AS (SELECT *,sum(octet_length(payload)) OVER (ORDER BY sequence) AS bytes FROM candidates) SELECT " + CopilotTaskRepository.MESSAGE + " FROM budgeted WHERE bytes<=#{maximumBytes} ORDER BY sequence")
    List<CopilotMessageEntity> source(String conversation, long after, long through, int limit, long maximumBytes);
    @Select("SELECT " + CopilotTaskRepository.MESSAGE + " FROM copilot_message WHERE task_id=#{task}::uuid AND kind='USER' AND sequence IN ((SELECT min(sequence) FROM copilot_message WHERE task_id=#{task}::uuid AND kind='USER'),(SELECT max(sequence) FROM copilot_message WHERE task_id=#{task}::uuid AND kind='USER')) ORDER BY sequence")
    List<CopilotMessageEntity> goals(String task);
    @Select("SELECT task_id::text FROM copilot_message WHERE task_id IN (SELECT id FROM copilot_task WHERE conversation_id=#{conversation}::uuid) ORDER BY sequence DESC LIMIT 1")
    String anchorTask(String conversation);
    @Select("""
        WITH foreign_origins AS (
          SELECT a.sequence FROM copilot_turn t JOIN copilot_message a ON a.id=t.assistant_message_id
          WHERE t.task_id IN (SELECT id FROM copilot_task WHERE conversation_id=#{conversation}::uuid) AND a.sequence>#{after}
            AND (t.connection_id<>#{connection}::uuid OR t.model_configuration_id<>#{model}::uuid
              OR t.connection_configuration_version<>#{connectionVersion} OR t.model_configuration_version<>#{modelVersion})
        ), ordered_messages AS (
          SELECT sequence,kind,max(sequence) FILTER (WHERE kind<>'TOOL') OVER (ORDER BY sequence) AS origin_sequence
          FROM copilot_message WHERE task_id IN (SELECT id FROM copilot_task WHERE conversation_id=#{conversation}::uuid) AND sequence>#{after}
        )
        SELECT coalesce(max(m.sequence),0) FROM ordered_messages m JOIN foreign_origins f ON f.sequence=m.origin_sequence
        WHERE m.kind IN ('ASSISTANT','TOOL')
        """)
    long foreignThrough(String conversation, long after, String connection, String model, long connectionVersion, long modelVersion);
    @Update("UPDATE copilot_turn SET estimated_input_tokens=#{estimate},context_input_bytes=#{bytes},context_version=#{version},context_summary_id=#{summary}::uuid,context_through_sequence=#{through},context_source_high_water=#{highWater},context_text_history_through=#{convertedThrough} WHERE id=#{turn}::uuid")
    void dispatchContext(String turn, long estimate, long bytes, long version, String summary, long through, long highWater, long convertedThrough);
}
