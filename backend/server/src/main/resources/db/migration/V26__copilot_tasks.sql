CREATE TABLE copilot_task (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_hash varchar(64) NOT NULL,
    request_key varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN ('QUEUED','RUNNING','PAUSED','STOPPING','STOPPED','SUCCEEDED','FAILED','INTERRUPTED')),
    reason varchar(128),
    stop_requested boolean NOT NULL DEFAULT false,
    last_progress_signature varchar(64),
    no_progress_count integer NOT NULL DEFAULT 0,
    worker_id uuid,
    worker_not_after timestamptz,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    last_checked_at timestamptz NOT NULL DEFAULT '-infinity',
    UNIQUE(exercise_id, request_key)
);
CREATE UNIQUE INDEX one_active_copilot_task ON copilot_task(exercise_id)
    WHERE state IN ('QUEUED','RUNNING','PAUSED','STOPPING');
CREATE INDEX copilot_task_discovery ON copilot_task(state, created_at, id);
CREATE INDEX copilot_task_maintenance ON copilot_task(state, last_checked_at, id);

CREATE TABLE copilot_message (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id uuid NOT NULL UNIQUE,
    task_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    kind varchar(32) NOT NULL CHECK (kind IN ('USER','ASSISTANT','TOOL')),
    payload text NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX copilot_message_context ON copilot_message(exercise_id, sequence);
CREATE INDEX copilot_message_task ON copilot_message(task_id, sequence);

CREATE TABLE copilot_turn (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    ordinal bigint NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN ('DISPATCHED','TOOLS','SUCCEEDED','FAILED','INTERRUPTED')),
    connection_id uuid NOT NULL,
    model_configuration_id uuid NOT NULL,
    connection_configuration_version bigint NOT NULL,
    model_configuration_version bigint NOT NULL,
    protocol varchar(32) NOT NULL,
    model_id varchar(256) NOT NULL,
    context_window integer NOT NULL,
    text_history boolean NOT NULL DEFAULT false,
    assistant_message_id uuid,
    tool_position integer NOT NULL DEFAULT 0,
    input_tokens bigint,
    output_tokens bigint,
    total_tokens bigint,
    usage_incomplete boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    not_after timestamptz NOT NULL,
    completed_at timestamptz,
    error_code varchar(128),
    UNIQUE(task_id, ordinal)
);
CREATE INDEX copilot_turn_exercise ON copilot_turn(exercise_id);
CREATE INDEX copilot_turn_recovery ON copilot_turn(state, not_after);

CREATE TABLE copilot_tool (
    id uuid PRIMARY KEY,
    turn_id uuid NOT NULL,
    task_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    position integer NOT NULL,
    call_id varchar(256) NOT NULL,
    name varchar(128) NOT NULL,
    arguments text NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN ('RUNNING','SUCCEEDED','INTERRUPTED')),
    result_message_id uuid,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    UNIQUE(turn_id, position)
);
CREATE INDEX copilot_tool_exercise ON copilot_tool(exercise_id);
