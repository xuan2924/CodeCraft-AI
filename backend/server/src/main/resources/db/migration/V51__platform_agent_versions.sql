CREATE TABLE platform_agent_role (
    role text PRIMARY KEY CHECK (role IN ('INTERVIEWER', 'EVALUATOR')),
    current_version uuid,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
INSERT INTO platform_agent_role(role) VALUES ('INTERVIEWER'), ('EVALUATOR');
CREATE TABLE platform_agent_version (
    id uuid PRIMARY KEY,
    role text NOT NULL CHECK (role IN ('INTERVIEWER', 'EVALUATOR')),
    definition jsonb NOT NULL,
    state text NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    credential_revision bigint NOT NULL DEFAULT 1 CHECK (credential_revision > 0),
    credential_key_id text,
    credential_nonce text,
    credential_ciphertext text,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    CHECK (state <> 'PUBLISHED' OR published_at IS NOT NULL),
    CHECK ((credential_key_id IS NULL AND credential_nonce IS NULL AND credential_ciphertext IS NULL)
        OR (credential_key_id IS NOT NULL AND credential_nonce IS NOT NULL AND credential_ciphertext IS NOT NULL))
);
CREATE INDEX platform_agent_version_catalog ON platform_agent_version(id DESC);
CREATE TABLE platform_agent_check (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY,
    version_id uuid NOT NULL,
    credential_revision bigint NOT NULL CHECK (credential_revision > 0),
    actor_id uuid NOT NULL,
    session_hash text NOT NULL,
    state text NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    created_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    finished_at timestamptz,
    error_code text,
    price_snapshot jsonb,
    usage_receipt jsonb,
    CHECK ((state IN ('SUCCEEDED', 'FAILED')) = (finished_at IS NOT NULL))
);
CREATE INDEX platform_agent_check_pending ON platform_agent_check(state, created_at);
CREATE INDEX platform_agent_check_version ON platform_agent_check(version_id, sequence DESC);
CREATE INDEX platform_agent_check_actor ON platform_agent_check(actor_id, created_at DESC);
CREATE TABLE platform_agent_command (
    actor_id uuid NOT NULL,
    kind text NOT NULL,
    command_key text NOT NULL,
    fingerprint text NOT NULL,
    resource_id uuid NOT NULL,
    PRIMARY KEY(actor_id, kind, command_key)
);
CREATE TABLE platform_exercise_binding (
    exercise_id uuid NOT NULL,
    role text NOT NULL CHECK (role IN ('INTERVIEWER', 'EVALUATOR')),
    version_id uuid NOT NULL,
    PRIMARY KEY(exercise_id, role)
);
CREATE INDEX platform_exercise_binding_version ON platform_exercise_binding(version_id);
