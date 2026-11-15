ALTER TABLE copilot_conversation ADD COLUMN fork_source uuid;
ALTER TABLE copilot_conversation ADD COLUMN fork_message uuid;
ALTER TABLE copilot_conversation ADD CONSTRAINT copilot_conversation_fork_pair CHECK ((fork_source IS NULL) = (fork_message IS NULL));

-- Logical references only. Immutable original protocol records stay owned by their originating tasks.
CREATE TABLE copilot_fork_message (
    conversation_id uuid NOT NULL,
    exercise_id uuid NOT NULL,
    message_id uuid NOT NULL,
    PRIMARY KEY (conversation_id, message_id)
);
CREATE INDEX copilot_fork_message_exercise ON copilot_fork_message(exercise_id);
CREATE VIEW copilot_conversation_message AS
    SELECT m.*, t.conversation_id FROM copilot_message m JOIN copilot_task t ON t.id=m.task_id
    UNION ALL
    SELECT m.*, f.conversation_id FROM copilot_message m JOIN copilot_fork_message f ON f.message_id=m.id;
