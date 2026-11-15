package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.dto.CopilotUsageContextDTO;
import ai.codecraft.modules.modelaccess.model.dto.ModelCostSubtotalResponse;
import ai.codecraft.modules.modelaccess.model.dto.ModelUsageTotalsResponse;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopilotUsageRepository {
    String CALLS = """
        WITH calls AS (
          SELECT 'CHAT'::text AS purpose,usage_facts,estimated_cost,cost_currency FROM copilot_turn WHERE exercise_id=#{exercise}::uuid
          UNION ALL SELECT 'SUMMARY',usage_facts,estimated_cost,cost_currency FROM copilot_compaction_call WHERE exercise_id=#{exercise}::uuid
        )
        """;

    @Select(CALLS + """
        SELECT purpose,count(*)::text AS calls,count(*) FILTER(WHERE usage_facts->>'complete'='true')::text AS complete_calls,
          sum((usage_facts->>'inputTokens')::numeric)::text AS input_tokens,
          sum((usage_facts->>'ordinaryInputTokens')::numeric)::text AS ordinary_input_tokens,
          sum((usage_facts->>'cacheReadTokens')::numeric)::text AS cache_read_tokens,
          sum((usage_facts->>'cacheWriteTokens')::numeric)::text AS cache_write_tokens,
          sum((usage_facts->>'outputTokens')::numeric)::text AS output_tokens,
          sum((usage_facts->>'totalTokens')::numeric)::text AS total_tokens,
          count(estimated_cost)::text AS priced_calls
        FROM calls GROUP BY purpose ORDER BY purpose
        """)
    List<ModelUsageTotalsResponse> totals(String exercise);

    @Select(CALLS + """
        SELECT purpose,cost_currency AS currency,sum(estimated_cost)::text AS amount,count(*)::text AS priced_calls
        FROM calls WHERE estimated_cost IS NOT NULL GROUP BY purpose,cost_currency ORDER BY purpose,cost_currency
        """)
    List<ModelCostSubtotalResponse> costs(String exercise);

    @Select("""
        SELECT id::text AS turn_id,model_id,context_window,context_version,usage_facts->>'inputTokens' AS input_tokens,created_at AS dispatched_at
        FROM copilot_turn WHERE exercise_id=#{exercise}::uuid
        ORDER BY created_at DESC,context_source_high_water DESC NULLS LAST,ordinal DESC,id DESC LIMIT 1
        """)
    CopilotUsageContextDTO latest(String exercise);
}
