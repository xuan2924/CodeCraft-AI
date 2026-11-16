ALTER TABLE workspace_test_task DROP CONSTRAINT workspace_test_task_target_check;
ALTER TABLE workspace_test_task ADD CONSTRAINT workspace_test_task_target_check CHECK (target IN ('base','challenge'));
ALTER TABLE copilot_test_run DROP CONSTRAINT copilot_test_run_target_check;
ALTER TABLE copilot_test_run ADD CONSTRAINT copilot_test_run_target_check CHECK (target IN ('base','challenge'));

CREATE TABLE workspace_challenge (
    exercise_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    entered_revision bigint NOT NULL CHECK (entered_revision >= 0),
    base_test_id uuid NOT NULL,
    entered_at timestamptz NOT NULL
);
