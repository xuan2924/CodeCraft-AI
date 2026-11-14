CREATE TABLE copilot_steer (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    task_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    actor_auth_version bigint NOT NULL CHECK (actor_auth_version > 0),
    request_key varchar(128) NOT NULL,
    fingerprint varchar(128) NOT NULL,
    text text NOT NULL,
    message_payload text NOT NULL,
    message_id uuid,
    state varchar(16) NOT NULL CHECK (state IN ('RECEIVED','APPLIED','NOT_APPLIED')),
    turn_id uuid,
    reason varchar(64),
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    UNIQUE (task_id, request_key),
    CHECK (octet_length(text) BETWEEN 1 AND 4096),
    CHECK (octet_length(message_payload) <= 32768),
    CHECK ((state = 'RECEIVED') = (completed_at IS NULL)),
    CHECK ((state = 'APPLIED') = (turn_id IS NOT NULL)),
    CHECK (state <> 'APPLIED' OR message_id IS NOT NULL),
    CHECK (state <> 'NOT_APPLIED' OR reason IS NOT NULL)
);
CREATE INDEX copilot_steer_task_order ON copilot_steer(task_id, sequence DESC);
CREATE INDEX copilot_steer_pending ON copilot_steer(task_id, sequence) WHERE state = 'RECEIVED';
CREATE INDEX copilot_steer_exercise ON copilot_steer(exercise_id);
