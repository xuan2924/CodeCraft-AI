ALTER TABLE copilot_task DROP CONSTRAINT copilot_task_state_check;
ALTER TABLE copilot_task ADD CONSTRAINT copilot_task_state_check CHECK (state IN (
    'QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','STOPPING','STOPPED','SUCCEEDED','FAILED','INTERRUPTED'));
DROP INDEX one_active_copilot_task;
CREATE UNIQUE INDEX one_active_copilot_task ON copilot_task(exercise_id)
    WHERE state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','STOPPING');
ALTER TABLE copilot_tool DROP CONSTRAINT copilot_tool_state_check;
ALTER TABLE copilot_tool ADD CONSTRAINT copilot_tool_state_check CHECK (state IN ('RUNNING','WAITING_APPROVAL','APPLYING','SUCCEEDED','INTERRUPTED'));
ALTER TABLE copilot_turn ADD COLUMN progress_signature varchar(64);

CREATE TABLE copilot_proposal (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    task_id uuid NOT NULL,
    tool_id uuid NOT NULL UNIQUE,
    turn_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    user_id uuid NOT NULL,
    workspace_instance_id uuid NOT NULL,
    base_revision bigint NOT NULL CHECK (base_revision >= 0),
    state varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','APPLYING','APPLIED','REJECTED','INVALIDATED','FAILED','CANCELLED')),
    mode varchar(16) NOT NULL DEFAULT 'ASK' CHECK (mode IN ('ASK','AUTO')),
    file_count integer NOT NULL CHECK (file_count BETWEEN 1 AND 32),
    batch_payload text NOT NULL,
    diff_payload text NOT NULL,
    fingerprint varchar(64) NOT NULL,
    operation_id uuid UNIQUE,
    decision_key varchar(128),
    decision_fingerprint varchar(64),
    decision_type varchar(16) CHECK (decision_type IN ('APPROVE','REJECT')),
    decision_actor uuid,
    decision_reason text CHECK (octet_length(decision_reason) <= 2048),
    result_revision bigint CHECK (result_revision >= 0),
    changed boolean,
    failure_code varchar(128),
    created_at timestamptz NOT NULL,
    decided_at timestamptz,
    completed_at timestamptz,
    CHECK ((decision_key IS NULL AND decision_fingerprint IS NULL AND decision_type IS NULL AND decided_at IS NULL)
        OR (decision_key IS NOT NULL AND decision_fingerprint IS NOT NULL AND decision_type IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX one_waiting_copilot_proposal ON copilot_proposal(task_id) WHERE state IN ('PENDING','APPLYING');
CREATE INDEX copilot_proposal_history ON copilot_proposal(task_id,sequence);
CREATE INDEX copilot_proposal_exercise ON copilot_proposal(exercise_id);
CREATE INDEX copilot_proposal_rejections ON copilot_proposal(task_id,fingerprint) WHERE state='REJECTED';
