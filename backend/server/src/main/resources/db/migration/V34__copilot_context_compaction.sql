CREATE TABLE copilot_context (
    exercise_id uuid PRIMARY KEY,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    summary_id uuid,
    through_sequence bigint NOT NULL DEFAULT 0 CHECK (through_sequence >= 0),
    updated_at timestamptz NOT NULL,
    CHECK ((version = 0 AND summary_id IS NULL AND through_sequence = 0)
        OR (version > 0 AND summary_id IS NOT NULL AND through_sequence > 0))
);

CREATE TABLE copilot_compaction (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    exercise_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_hash varchar(64) NOT NULL,
    actor_auth_version bigint NOT NULL,
    anchor_task_id uuid,
    bound_task_id uuid,
    request_key varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    trigger_kind varchar(16) NOT NULL CHECK (trigger_kind IN ('MANUAL','AUTO')),
    state varchar(32) NOT NULL CHECK (state IN ('WAITING','RUNNING','APPLIED','FAILED','INTERRUPTED','CANCELLED','NOT_NEEDED')),
    stop_requested boolean NOT NULL DEFAULT false,
    source_version bigint,
    source_summary_id uuid,
    through_sequence bigint,
    source_ids_payload text,
    summary_payload text,
    failure_code varchar(128),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 2),
    worker_id uuid,
    worker_task_id uuid,
    worker_not_after timestamptz,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    last_checked_at timestamptz NOT NULL DEFAULT '-infinity',
    UNIQUE(exercise_id, request_key),
    CHECK ((state IN ('WAITING','RUNNING') AND completed_at IS NULL)
        OR (state NOT IN ('WAITING','RUNNING') AND completed_at IS NOT NULL)),
    CHECK (state <> 'APPLIED' OR (summary_payload IS NOT NULL AND through_sequence > 0 AND source_version IS NOT NULL))
);
CREATE UNIQUE INDEX one_pending_copilot_compaction ON copilot_compaction(exercise_id) WHERE state IN ('WAITING','RUNNING');
CREATE INDEX copilot_compaction_discovery ON copilot_compaction(state, last_checked_at, sequence);
CREATE INDEX copilot_compaction_exercise ON copilot_compaction(exercise_id, sequence DESC);
CREATE INDEX copilot_compaction_parent ON copilot_compaction(bound_task_id) WHERE state IN ('WAITING','RUNNING');
CREATE INDEX copilot_compaction_worker_task ON copilot_compaction(worker_task_id);

CREATE TABLE copilot_compaction_call (
    id uuid PRIMARY KEY,
    compaction_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 1 AND 2),
    state varchar(32) NOT NULL CHECK (state IN ('DISPATCHED','SUCCEEDED','FAILED','INTERRUPTED')),
    connection_id uuid NOT NULL,
    model_configuration_id uuid NOT NULL,
    connection_configuration_version bigint NOT NULL,
    model_configuration_version bigint NOT NULL,
    protocol varchar(32) NOT NULL,
    model_id varchar(256) NOT NULL,
    context_window integer NOT NULL,
    output_limit integer NOT NULL,
    source_version bigint NOT NULL,
    source_summary_id uuid,
    through_sequence bigint NOT NULL,
    source_ids_payload text NOT NULL,
    estimated_input_tokens bigint NOT NULL,
    configuration_payload text NOT NULL,
    response_payload text,
    input_tokens bigint,
    output_tokens bigint,
    total_tokens bigint,
    usage_incomplete boolean NOT NULL DEFAULT true,
    usage_payload text,
    error_code varchar(128),
    created_at timestamptz NOT NULL,
    not_after timestamptz NOT NULL,
    completed_at timestamptz,
    UNIQUE(compaction_id, ordinal)
);
CREATE INDEX copilot_compaction_call_exercise ON copilot_compaction_call(exercise_id);
CREATE INDEX copilot_compaction_failed_source ON copilot_compaction_call(exercise_id, source_version, through_sequence);

ALTER TABLE copilot_turn ADD COLUMN estimated_input_tokens bigint;
ALTER TABLE copilot_turn ADD COLUMN context_input_bytes bigint;
ALTER TABLE copilot_turn ADD COLUMN context_version bigint;
ALTER TABLE copilot_turn ADD COLUMN context_summary_id uuid;
ALTER TABLE copilot_turn ADD COLUMN context_through_sequence bigint;
ALTER TABLE copilot_turn ADD COLUMN context_source_high_water bigint;
ALTER TABLE copilot_turn ADD COLUMN context_text_history_through bigint;
