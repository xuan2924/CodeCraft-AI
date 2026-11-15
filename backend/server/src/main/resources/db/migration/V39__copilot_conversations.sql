CREATE TABLE copilot_conversation (
    id uuid PRIMARY KEY,
    exercise_id uuid NOT NULL,
    request_key varchar(128) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (exercise_id, request_key)
);
CREATE INDEX copilot_conversation_exercise ON copilot_conversation(exercise_id, created_at, id);

-- Stable initial identity preserves the existing exercise-scoped history without copying it.
INSERT INTO copilot_conversation(id, exercise_id, request_key, created_at)
SELECT id, id, 'initial', created_at FROM workspace_exercise;

ALTER TABLE copilot_task ADD COLUMN conversation_id uuid;
UPDATE copilot_task SET conversation_id=exercise_id;
ALTER TABLE copilot_task ALTER COLUMN conversation_id SET NOT NULL;
CREATE INDEX copilot_task_conversation ON copilot_task(conversation_id, created_at, id);

