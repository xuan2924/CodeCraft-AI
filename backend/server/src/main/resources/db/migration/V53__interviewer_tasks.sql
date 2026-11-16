CREATE TABLE workspace_interviewer_task (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_hash varchar(128) NOT NULL,
    control_hash varchar(128) NOT NULL,
    request_key varchar(128) NOT NULL,
    question text NOT NULL CHECK (octet_length(question) BETWEEN 1 AND 4096),
    answer text CHECK (octet_length(answer) <= 16384),
    state varchar(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    reason varchar(128),
    platform_version_id uuid NOT NULL,
    phase varchar(16) NOT NULL CHECK (phase IN ('READY','ACTIVE')),
    revision bigint,
    created_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    completed_at timestamptz,
    usage_receipt text,
    price_snapshot text,
    UNIQUE (exercise_id,request_key)
);
CREATE UNIQUE INDEX workspace_interviewer_one_active ON workspace_interviewer_task(exercise_id) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX workspace_interviewer_history ON workspace_interviewer_task(exercise_id,created_at DESC,id DESC);
CREATE INDEX workspace_interviewer_discovery ON workspace_interviewer_task(deadline_at,id) WHERE state IN ('QUEUED','RUNNING');
