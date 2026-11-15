ALTER TABLE copilot_task DROP CONSTRAINT copilot_task_lane_check;
ALTER TABLE copilot_task ADD CONSTRAINT copilot_task_lane_check CHECK (lane IN ('MAIN','BTW','CHILD'));
ALTER TABLE copilot_task DROP CONSTRAINT copilot_task_state_check;
ALTER TABLE copilot_task ADD CONSTRAINT copilot_task_state_check CHECK (state IN (
    'QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING','STOPPED','SUCCEEDED','FAILED','INTERRUPTED'));
ALTER TABLE copilot_tool DROP CONSTRAINT copilot_tool_state_check;
ALTER TABLE copilot_tool ADD CONSTRAINT copilot_tool_state_check CHECK (state IN (
    'RUNNING','WAITING_APPROVAL','APPLYING','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','SUCCEEDED','INTERRUPTED'));
DROP INDEX one_active_copilot_task;
CREATE UNIQUE INDEX one_active_copilot_task ON copilot_task(exercise_id,lane)
    WHERE state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','WAITING_CHILD','STOPPING');
CREATE TABLE copilot_delegation (
    tool_id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    parent_task_id uuid NOT NULL,
    child_task_id uuid NOT NULL UNIQUE,
    conversation_id uuid NOT NULL UNIQUE,
    goal text NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('WAITING','RESOLVED')),
    result_payload text,
    result_message_id uuid,
    created_at timestamptz NOT NULL,
    completed_at timestamptz
);
CREATE UNIQUE INDEX one_waiting_copilot_delegation ON copilot_delegation(parent_task_id) WHERE state='WAITING';
CREATE INDEX copilot_delegation_exercise ON copilot_delegation(exercise_id);
CREATE INDEX copilot_delegation_parent ON copilot_delegation(parent_task_id,created_at,tool_id);
