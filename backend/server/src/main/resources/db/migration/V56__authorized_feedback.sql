CREATE TABLE workspace_feedback (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    user_id uuid NOT NULL,
    request_key varchar(128) NOT NULL,
    request_fingerprint varchar(128) NOT NULL,
    snapshot text NOT NULL CHECK (octet_length(snapshot) <= 65536),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','HANDLED')),
    reply text CHECK (octet_length(reply) <= 4096),
    reply_key varchar(128),
    created_at timestamptz NOT NULL,
    handled_at timestamptz,
    UNIQUE(exercise_id, request_key)
);
CREATE INDEX workspace_feedback_inbox ON workspace_feedback(id);
CREATE INDEX workspace_feedback_owner ON workspace_feedback(user_id, id);
