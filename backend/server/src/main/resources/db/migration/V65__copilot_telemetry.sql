-- Inline fixed-size observations on existing idempotent checkpoints: no second write path or replay.
ALTER TABLE copilot_turn ADD COLUMN micro_saved_bytes bigint CHECK (micro_saved_bytes >= 0),
    ADD COLUMN model_returned_at timestamptz;
ALTER TABLE copilot_tool ADD COLUMN read_window_width smallint CHECK (read_window_width BETWEEN 0 AND 4),
    ADD COLUMN revision_conflict boolean;
CREATE INDEX copilot_tool_task_observation ON copilot_tool(task_id);
CREATE INDEX copilot_task_recent_observation ON copilot_task(exercise_id, created_at DESC, id DESC);

-- Aggregate each child relation independently: a four-tool turn is still only one model request.
CREATE VIEW copilot_task_telemetry AS
SELECT t.id, t.exercise_id, t.created_at, t.state, m.*, x.*, d.explore_children
FROM copilot_task t
CROSS JOIN LATERAL (
    SELECT count(*) AS rounds,
        sum(extract(epoch FROM (model_returned_at-created_at))*1000)::bigint AS model_elapsed_ms,
        count(*) FILTER (WHERE micro_saved_bytes>0) AS micro_compacted_rounds,
        sum(micro_saved_bytes)::bigint AS micro_saved_bytes,
        count(*) FILTER (WHERE error_code='STEER_SUPERSEDED') AS steer_interruptions,
        sum((usage_facts->>'inputTokens')::bigint)::bigint AS input_tokens,
        sum((usage_facts->>'outputTokens')::bigint)::bigint AS output_tokens,
        sum((usage_facts->>'cacheReadTokens')::bigint)::bigint AS cache_read_tokens,
        sum((usage_facts->>'cacheWriteTokens')::bigint)::bigint AS cache_write_tokens
    FROM copilot_turn WHERE task_id=t.id
) m
CROSS JOIN LATERAL (
    SELECT count(*) AS tools,
        sum(extract(epoch FROM (completed_at-created_at))*1000)::bigint AS tool_elapsed_ms,
        count(*) FILTER (WHERE read_window_width>0) AS read_windows,
        sum(read_window_width)::bigint AS read_slots_used,
        count(*) FILTER (WHERE revision_conflict AND t.state='SUCCEEDED' AND EXISTS (
            SELECT 1 FROM copilot_tool r JOIN copilot_turn read_turn ON read_turn.id=r.turn_id
            JOIN copilot_turn stale_turn ON stale_turn.id=tool.turn_id
            WHERE r.task_id=t.id AND r.name IN ('read','view_file') AND r.outcome_code='OK'
                AND read_turn.ordinal>stale_turn.ordinal
                AND EXISTS (SELECT 1 FROM copilot_turn next WHERE next.task_id=t.id AND next.ordinal>read_turn.ordinal)
        )) AS recovered_revision_conflicts
    FROM copilot_tool tool WHERE task_id=t.id
) x
CROSS JOIN LATERAL (
    SELECT count(*) AS explore_children FROM copilot_delegation WHERE parent_task_id=t.id AND mode='EXPLORE'
) d;
