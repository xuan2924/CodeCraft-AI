CREATE TABLE workspace_report (
    exercise_id uuid PRIMARY KEY,
    id uuid NOT NULL UNIQUE,
    user_id uuid NOT NULL,
    session_hash varchar(128) NOT NULL,
    platform_version_id uuid NOT NULL,
    final_revision bigint NOT NULL CHECK (final_revision >= 0),
    verification varchar(24) NOT NULL CHECK (verification IN ('PASSED','FAILED','TIMED_OUT','UNAVAILABLE')),
    final_test_id uuid,
    review_skipped boolean NOT NULL,
    review_text text NOT NULL CHECK (octet_length(review_text) <= 4096),
    operation_id uuid NOT NULL,
    generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
    state varchar(24) NOT NULL CHECK (state IN ('QUEUED','PREPARING','RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    attempt_id uuid,
    deadline_at timestamptz,
    evidence jsonb,
    evaluation jsonb,
    reason varchar(128),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (NOT review_skipped OR review_text='')
);
CREATE TABLE workspace_report_attempt (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    generation bigint NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    dispatched_at timestamptz NOT NULL,
    completed_at timestamptz,
    price_snapshot text,
    usage_receipt text,
    response_text text CHECK (octet_length(response_text) <= 65536),
    reason varchar(128)
);
CREATE INDEX workspace_report_pending ON workspace_report(state,updated_at) WHERE state IN ('QUEUED','PREPARING','RUNNING');
CREATE INDEX workspace_report_attempt_scope ON workspace_report_attempt(exercise_id,generation);
