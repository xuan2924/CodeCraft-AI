ALTER TABLE workspace_exercise DROP CONSTRAINT workspace_exercise_state_check;
ALTER TABLE workspace_exercise ADD CONSTRAINT workspace_exercise_state_check CHECK (state IN (
    'PREPARING', 'READY', 'PREPARATION_FAILED', 'ACTIVE', 'PAUSING', 'PAUSED', 'RESUMING',
    'CLOSING', 'FINAL_TESTING', 'ENDED'
));

ALTER TABLE workspace_transition DROP CONSTRAINT workspace_transition_kind_check;
ALTER TABLE workspace_transition ADD CONSTRAINT workspace_transition_kind_check CHECK (kind IN (
    'ACQUIRE', 'TAKEOVER', 'RELEASE', 'RECONCILE', 'REVOKE', 'END', 'PAUSE', 'RESUME'
));

CREATE TABLE workspace_pause (
    exercise_id uuid PRIMARY KEY,
    pause_operation_id uuid NOT NULL,
    resume_operation_id uuid,
    remaining_millis bigint CHECK (remaining_millis > 0),
    confirmed_at timestamptz,
    last_attempt_at timestamptz NOT NULL DEFAULT 'epoch',
    CHECK ((remaining_millis IS NULL) = (confirmed_at IS NULL))
);

