ALTER TABLE workspace_exercise ADD COLUMN retention_seconds bigint NOT NULL DEFAULT 2592000 CHECK (retention_seconds > 0);
ALTER TABLE workspace_exercise ADD COLUMN ended_at timestamptz;
ALTER TABLE workspace_exercise ADD COLUMN retain_until timestamptz;
-- Legacy ended exercises receive no shorter than a fresh 30-day promise when
-- the original end time was not persisted (preparation abandon).
UPDATE workspace_exercise e SET ended_at = coalesce(f.ended_at, clock_timestamp()),
    retain_until = coalesce(f.ended_at, clock_timestamp()) + interval '30 days'
FROM workspace_finalization f WHERE e.id = f.exercise_id AND e.state = 'ENDED';
UPDATE workspace_exercise SET ended_at = clock_timestamp(), retain_until = clock_timestamp() + interval '30 days'
WHERE state = 'ENDED' AND ended_at IS NULL;
CREATE INDEX workspace_exercise_expiry ON workspace_exercise(retain_until, id) WHERE state = 'ENDED';

CREATE TABLE workspace_deletion (
    exercise_id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    workspace_instance_id uuid NOT NULL,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RUNNING', 'FAILED', 'SUCCEEDED')),
    requested_at timestamptz NOT NULL,
    completed_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    first_failure_at timestamptz,
    failure_code text,
    claim_id text
);
CREATE INDEX workspace_deletion_retry ON workspace_deletion(next_attempt_at, exercise_id) WHERE state <> 'SUCCEEDED';
