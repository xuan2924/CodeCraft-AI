ALTER TABLE copilot_task DROP CONSTRAINT copilot_task_state_check;
ALTER TABLE copilot_task ADD CONSTRAINT copilot_task_state_check CHECK (state IN (
    'QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','STOPPING','STOPPED','SUCCEEDED','FAILED','INTERRUPTED'));
DROP INDEX one_active_copilot_task;
CREATE UNIQUE INDEX one_active_copilot_task ON copilot_task(exercise_id)
    WHERE state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','STOPPING');
ALTER TABLE copilot_tool DROP CONSTRAINT copilot_tool_state_check;
ALTER TABLE copilot_tool ADD CONSTRAINT copilot_tool_state_check CHECK (state IN (
    'RUNNING','WAITING_APPROVAL','APPLYING','WAITING_TEST','WAITING_COMMAND','SUCCEEDED','INTERRUPTED'));

-- Logical references are validated by services under the resource-owner transaction.
-- id is the original tool id; one decision can bind at most one execution.
CREATE TABLE copilot_command (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    task_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    user_id uuid NOT NULL,
    workspace_instance_id uuid NOT NULL,
    base_revision bigint NOT NULL CHECK (base_revision >= 0),
    user_sequence bigint NOT NULL CHECK (user_sequence > 0),
    command_text text NOT NULL CHECK (octet_length(command_text) BETWEEN 1 AND 4096),
    working_directory text NOT NULL CHECK (octet_length(working_directory) BETWEEN 1 AND 1024),
    fingerprint varchar(128) NOT NULL,
    mode varchar(8) NOT NULL CHECK (mode IN ('ASK','AUTO')),
    mode_version bigint NOT NULL CHECK (mode_version >= 0),
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','WAITING','RESOLVED')),
    command_task_id uuid UNIQUE,
    decision_key varchar(128),
    decision_fingerprint varchar(128),
    decision varchar(16) CHECK (decision IN ('APPROVE','REJECT')),
    decision_actor uuid,
    actor_auth_version bigint,
    reason text CHECK (octet_length(reason) <= 2048),
    result_payload text CHECK (octet_length(result_payload) <= 262144),
    created_at timestamptz NOT NULL,
    decided_at timestamptz,
    completed_at timestamptz,
    CHECK ((decision IS NULL AND decision_key IS NULL AND decision_fingerprint IS NULL AND decision_actor IS NULL AND actor_auth_version IS NULL AND decided_at IS NULL)
        OR (decision IS NOT NULL AND decision_key IS NOT NULL AND decision_fingerprint IS NOT NULL AND decision_actor IS NOT NULL AND actor_auth_version IS NOT NULL AND decided_at IS NOT NULL)),
    CHECK ((state='PENDING' AND command_task_id IS NULL AND result_payload IS NULL AND completed_at IS NULL)
        OR (state='WAITING' AND command_task_id IS NOT NULL AND decision='APPROVE' AND result_payload IS NULL AND completed_at IS NULL)
        OR (state='RESOLVED' AND result_payload IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX one_pending_copilot_command ON copilot_command(task_id) WHERE state IN ('PENDING','WAITING');
CREATE INDEX copilot_command_exercise ON copilot_command(exercise_id);
CREATE INDEX copilot_command_page ON copilot_command(task_id,sequence DESC);
CREATE INDEX copilot_command_rejection ON copilot_command(task_id,user_sequence,fingerprint) WHERE decision='REJECT';
