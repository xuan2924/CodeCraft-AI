ALTER TABLE copilot_task ADD COLUMN lane varchar(16) NOT NULL DEFAULT 'MAIN' CHECK (lane IN ('MAIN','BTW'));
DROP INDEX one_active_copilot_task;
CREATE UNIQUE INDEX one_active_copilot_task ON copilot_task(exercise_id,lane)
    WHERE state IN ('QUEUED','RUNNING','PAUSED','WAITING_APPROVAL','WAITING_WRITE','WAITING_TEST','WAITING_COMMAND','STOPPING');
DROP INDEX one_pending_copilot_compaction;
CREATE UNIQUE INDEX one_pending_copilot_compaction ON copilot_compaction(conversation_id) WHERE state IN ('WAITING','RUNNING');
CREATE TABLE copilot_btw (
    parent_conversation_id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL UNIQUE,
    exercise_id uuid NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX copilot_btw_exercise ON copilot_btw(exercise_id);
