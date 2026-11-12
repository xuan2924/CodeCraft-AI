ALTER TABLE workspace_control DROP CONSTRAINT workspace_control_gate_state_check;
ALTER TABLE workspace_control ADD CONSTRAINT workspace_control_gate_state_check CHECK (gate_state IN ('OPEN', 'CLOSED', 'FROZEN'));
ALTER TABLE workspace_transition DROP CONSTRAINT workspace_transition_kind_check;
ALTER TABLE workspace_transition ADD CONSTRAINT workspace_transition_kind_check CHECK (kind IN ('ACQUIRE', 'TAKEOVER', 'RELEASE', 'RECONCILE', 'REVOKE', 'END'));

CREATE TABLE workspace_finalization (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL UNIQUE,
    frozen_epoch bigint CHECK (frozen_epoch >= 0),
    final_revision bigint CHECK (final_revision >= 0),
    started_at timestamptz,
    deadline_at timestamptz,
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 3),
    budget_seconds integer NOT NULL CHECK (budget_seconds BETWEEN 1 AND 600),
    first_retry_seconds integer NOT NULL CHECK (first_retry_seconds > 0),
    second_retry_seconds integer NOT NULL CHECK (second_retry_seconds >= first_retry_seconds),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0 AND attempts <= max_attempts),
    current_task_id uuid,
    next_attempt_at timestamptz,
    verification varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (verification IN ('PENDING', 'PASSED', 'FAILED', 'TIMED_OUT', 'UNAVAILABLE')),
    result_task_id uuid,
    ended_at timestamptz,
    cancellation_pending boolean NOT NULL DEFAULT true,
    last_attempt_at timestamptz NOT NULL DEFAULT '-infinity',
    CHECK ((frozen_epoch IS NULL) = (final_revision IS NULL)),
    CHECK ((started_at IS NULL) = (deadline_at IS NULL))
);
CREATE INDEX workspace_finalization_pending ON workspace_finalization(last_attempt_at, id) WHERE ended_at IS NULL OR cancellation_pending;

CREATE TABLE workspace_final_task (
    task_id uuid PRIMARY KEY,
    finalization_id uuid NOT NULL,
    freeze_operation_id uuid NOT NULL,
    deadline_at timestamptz NOT NULL
);
