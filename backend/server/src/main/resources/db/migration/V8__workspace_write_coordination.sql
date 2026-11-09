CREATE TABLE workspace_control (
    exercise_id uuid PRIMARY KEY REFERENCES workspace_exercise(id),
    generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0),
    gate_epoch bigint,
    gate_state varchar(16) NOT NULL DEFAULT 'CLOSED' CHECK (gate_state IN ('OPEN', 'CLOSED')),
    credential_hash varchar(64),
    transition_id uuid,
    fence_id uuid
);
CREATE TABLE workspace_transition (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL REFERENCES workspace_exercise(id),
    kind varchar(32) NOT NULL CHECK (kind IN ('ACQUIRE', 'TAKEOVER', 'RELEASE', 'RECONCILE', 'REVOKE')),
    expected_epoch bigint,
    phase varchar(16) NOT NULL DEFAULT 'CLOSING' CHECK (phase IN ('CLOSING', 'OPENING', 'SUCCEEDED')),
    target_hash varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz NOT NULL DEFAULT '-infinity'
);
CREATE INDEX workspace_transition_pending ON workspace_transition(last_attempt_at, id) WHERE phase <> 'SUCCEEDED';
CREATE TABLE workspace_mutation (
    operation_id uuid PRIMARY KEY REFERENCES workspace_operation(id),
    payload text NOT NULL,
    gate_epoch bigint NOT NULL,
    not_after timestamptz NOT NULL,
    dispatched boolean NOT NULL DEFAULT false,
    revision bigint,
    changed boolean,
    failure_code varchar(64)
);
