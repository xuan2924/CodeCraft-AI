CREATE TABLE workspace_exercise (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_user(id),
    problem_version_id uuid NOT NULL REFERENCES problem_version(id),
    workspace_instance_id uuid NOT NULL UNIQUE,
    preparation_operation_id uuid NOT NULL UNIQUE,
    state varchar(32) NOT NULL CHECK (state IN ('PREPARING', 'READY', 'PREPARATION_FAILED', 'ACTIVE', 'CLOSING', 'FINAL_TESTING', 'ENDED')),
    revision bigint CHECK (revision >= 0),
    paths jsonb NOT NULL DEFAULT '[]',
    state_version bigint NOT NULL DEFAULT 1 CHECK (state_version > 0),
    created_at timestamptz NOT NULL,
    started_at timestamptz,
    deadline_at timestamptz
);
CREATE UNIQUE INDEX one_unfinished_exercise_per_user ON workspace_exercise(user_id) WHERE state <> 'ENDED';

CREATE TABLE workspace_operation (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_user(id),
    exercise_id uuid NOT NULL REFERENCES workspace_exercise(id),
    workspace_instance_id uuid NOT NULL,
    kind varchar(32) NOT NULL,
    target varchar(64) NOT NULL,
    request_key varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN ('PENDING', 'PENDING_RECONCILIATION', 'SUCCEEDED', 'REJECTED')),
    created_at timestamptz NOT NULL,
    UNIQUE(user_id, kind, target, request_key)
);
CREATE INDEX workspace_pending_operations ON workspace_operation(created_at, id)
    WHERE state IN ('PENDING', 'PENDING_RECONCILIATION');
