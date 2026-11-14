ALTER TABLE copilot_task DROP CONSTRAINT copilot_task_state_check;
ALTER TABLE copilot_task ADD CONSTRAINT copilot_task_state_check CHECK (state IN (
    'QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','STOPPING','STOPPED','SUCCEEDED','FAILED','INTERRUPTED'));
DROP INDEX one_active_copilot_task;
CREATE UNIQUE INDEX one_active_copilot_task ON copilot_task(exercise_id)
    WHERE state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','STOPPING');
ALTER TABLE copilot_tool DROP CONSTRAINT copilot_tool_state_check;
ALTER TABLE copilot_tool ADD CONSTRAINT copilot_tool_state_check CHECK (state IN ('RUNNING','WAITING_APPROVAL','APPLYING','WAITING_TEST','SUCCEEDED','INTERRUPTED'));

CREATE TABLE copilot_test_run (
    tool_id uuid PRIMARY KEY,
    task_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    workspace_instance_id uuid NOT NULL,
    test_task_id uuid NOT NULL UNIQUE,
    user_sequence bigint NOT NULL CHECK (user_sequence > 0),
    revision bigint NOT NULL CHECK (revision >= 0),
    target varchar(32) NOT NULL CHECK (target = 'base'),
    state varchar(16) NOT NULL DEFAULT 'WAITING' CHECK (state IN ('WAITING','RESOLVED')),
    result_state varchar(32) CHECK (result_state IN ('PASSED','FAILED','TIMED_OUT','STOPPED','ENVIRONMENT_ERROR','INTERRUPTED')),
    result_version bigint CHECK (result_version >= 0),
    result_payload text CHECK (octet_length(result_payload) <= 262144),
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    UNIQUE(task_id,user_sequence,revision,target),
    CHECK ((state='WAITING' AND result_state IS NULL AND result_version IS NULL AND result_payload IS NULL AND completed_at IS NULL)
        OR (state='RESOLVED' AND result_state IS NOT NULL AND result_version IS NOT NULL AND result_payload IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX one_waiting_copilot_test ON copilot_test_run(task_id) WHERE state='WAITING';
CREATE INDEX copilot_test_exercise ON copilot_test_run(exercise_id);
CREATE INDEX copilot_test_history ON copilot_test_run(task_id,created_at);
