CREATE TABLE identity_user (
    id uuid PRIMARY KEY,
    username varchar(32) NOT NULL UNIQUE,
    password_hash text NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    password_change_required boolean NOT NULL DEFAULT true,
    auth_version bigint NOT NULL DEFAULT 1,
    CHECK (username ~ '^[a-z0-9_-]{3,32}$')
);

CREATE TABLE identity_session (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity_user(id),
    auth_version bigint NOT NULL,
    csrf_token varchar(64) NOT NULL,
    issued_at timestamptz NOT NULL,
    last_activity timestamptz NOT NULL,
    revoked boolean NOT NULL DEFAULT false
);
CREATE INDEX identity_session_user ON identity_session(user_id);

CREATE TABLE identity_anonymous_csrf (
    token_hash char(64) PRIMARY KEY,
    csrf_token varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX identity_anonymous_expiry ON identity_anonymous_csrf(expires_at);

CREATE TABLE identity_rate_limit (
    bucket_hash char(64) PRIMARY KEY,
    window_start timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 5)
);

-- No plaintext passwords or successful credential-response bodies are persisted.
CREATE TABLE identity_receipt (
    actor_id uuid NOT NULL REFERENCES identity_user(id),
    action varchar(32) NOT NULL,
    target varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    fingerprint char(64) NOT NULL,
    operation_id uuid NOT NULL UNIQUE,
    result_user_id uuid NOT NULL REFERENCES identity_user(id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, action, target, idempotency_key)
);
