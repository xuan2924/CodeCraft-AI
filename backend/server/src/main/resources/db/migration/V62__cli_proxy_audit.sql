CREATE TABLE model_proxy_call (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    endpoint text NOT NULL,
    state text NOT NULL DEFAULT 'STARTED',
    request_bytes integer NOT NULL,
    response_bytes bigint NOT NULL DEFAULT 0,
    tool_signals integer NOT NULL DEFAULT 0,
    reasoning_tokens bigint,
    usage_facts jsonb,
    price_snapshot jsonb NOT NULL,
    cost_currency text,
    estimated_cost numeric,
    cost_reason text
);
CREATE INDEX model_proxy_call_exercise ON model_proxy_call(exercise_id, created_at);
