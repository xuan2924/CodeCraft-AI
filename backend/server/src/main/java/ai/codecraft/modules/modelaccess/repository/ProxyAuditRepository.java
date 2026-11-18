package ai.codecraft.modules.modelaccess.repository;

import ai.codecraft.modules.modelaccess.model.dto.ModelUsageReceiptDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelCostSubtotalResponse;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProxyAuditRepository {
    record Event(String id, Instant createdAt, Instant completedAt, String endpoint, String state, int requestBytes,
        long responseBytes, int toolSignals, Long reasoningTokens) { }
    @Insert("INSERT INTO model_proxy_call(id,exercise_id,created_at,endpoint,request_bytes,price_snapshot) VALUES(#{id}::uuid,#{exercise}::uuid,#{now},#{endpoint},#{bytes},#{price}::jsonb)")
    void start(String id, String exercise, Instant now, String endpoint, int bytes, String price);
    @Update("""
        UPDATE model_proxy_call SET completed_at=#{now},state=#{state},response_bytes=#{bytes},tool_signals=#{tools},reasoning_tokens=#{reasoning},
          usage_facts=#{receipt.factsPayload}::jsonb,cost_currency=#{receipt.currency},estimated_cost=#{receipt.amount},cost_reason=#{receipt.priceReason}
        WHERE id=#{id}::uuid AND completed_at IS NULL
        """)
    void finish(String id, Instant now, String state, long bytes, int tools, Long reasoning, ModelUsageReceiptDTO receipt);
    @Select("SELECT id::text,created_at,completed_at,endpoint,state,request_bytes,response_bytes,tool_signals,reasoning_tokens FROM model_proxy_call WHERE exercise_id=#{exercise}::uuid ORDER BY created_at,id")
    List<Event> events(String exercise);
    @Select("""
        SELECT 'CLI_PROXY' AS purpose,count(*)::text AS calls,count(*) FILTER(WHERE usage_facts->>'complete'='true')::text AS complete_calls,
          sum((usage_facts->>'inputTokens')::numeric)::text AS input_tokens,
          sum((usage_facts->>'ordinaryInputTokens')::numeric)::text AS ordinary_input_tokens,
          sum((usage_facts->>'cacheReadTokens')::numeric)::text AS cache_read_tokens,
          sum((usage_facts->>'cacheWriteTokens')::numeric)::text AS cache_write_tokens,
          sum((usage_facts->>'outputTokens')::numeric)::text AS output_tokens,
          sum((usage_facts->>'totalTokens')::numeric)::text AS total_tokens,count(estimated_cost)::text AS priced_calls
        FROM model_proxy_call WHERE exercise_id=#{exercise}::uuid HAVING count(*)>0
        """)
    List<ModelUsageTotalsResponse> totals(String exercise);
    @Select("SELECT 'CLI_PROXY' AS purpose,cost_currency AS currency,sum(estimated_cost)::text AS amount,count(*)::text AS priced_calls FROM model_proxy_call WHERE exercise_id=#{exercise}::uuid AND estimated_cost IS NOT NULL GROUP BY cost_currency")
    List<ModelCostSubtotalResponse> costs(String exercise);
    @Select("SELECT coalesce(sum(pg_column_size(c) + CASE WHEN completed_at IS NULL THEN 4096 ELSE 0 END),0) FROM model_proxy_call c WHERE exercise_id=#{exercise}::uuid")
    long bytes(String exercise);
    @Select("SELECT NOT EXISTS(SELECT 1 FROM model_proxy_call WHERE exercise_id=#{exercise}::uuid AND completed_at IS NULL AND created_at>clock_timestamp()-interval '6 minutes')")
    boolean quiescent(String exercise);
    @org.apache.ibatis.annotations.Delete("DELETE FROM model_proxy_call WHERE exercise_id=#{exercise}::uuid")
    void delete(String exercise);
}
