package ai.codecraft.modules.copilot.repository;

import ai.codecraft.modules.copilot.model.dto.CopilotTelemetryResponse.Task;
import ai.codecraft.modules.copilot.model.dto.CopilotTelemetryResponse.Totals;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CopilotTelemetryRepository {
    String TOTALS = """
        SELECT count(*) AS task_count, coalesce(sum(rounds),0) AS rounds, coalesce(sum(tools),0) AS tools,
          sum(model_elapsed_ms) AS model_elapsed_ms, sum(tool_elapsed_ms) AS tool_elapsed_ms,
          coalesce(sum(read_windows),0) AS read_windows, sum(read_slots_used) AS read_slots_used,
          coalesce(sum(explore_children),0) AS explore_children, coalesce(sum(micro_compacted_rounds),0) AS micro_compacted_rounds,
          sum(micro_saved_bytes) AS micro_saved_bytes, coalesce(sum(recovered_revision_conflicts),0) AS recovered_revision_conflicts,
          coalesce(sum(steer_interruptions),0) AS steer_interruptions, sum(input_tokens) AS chat_input_tokens,
          sum(output_tokens) AS chat_output_tokens, sum(cache_read_tokens) AS chat_cache_read_tokens, sum(cache_write_tokens) AS chat_cache_write_tokens
        FROM copilot_task_telemetry
        """;

    @Select(TOTALS)
    Totals platform();

    @Select(TOTALS + " WHERE exercise_id=#{exercise}::uuid")
    Totals exercise(String exercise);

    @Select("""
        SELECT id::text AS task_id,state,created_at,rounds,tools,model_elapsed_ms,tool_elapsed_ms,micro_saved_bytes
        FROM copilot_task_telemetry WHERE exercise_id=#{exercise}::uuid ORDER BY created_at DESC,id DESC LIMIT 20
        """)
    List<Task> recent(String exercise);
}
