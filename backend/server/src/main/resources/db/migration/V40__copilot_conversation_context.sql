ALTER TABLE copilot_context ADD COLUMN conversation_id uuid;
UPDATE copilot_context SET conversation_id=exercise_id;
ALTER TABLE copilot_context ALTER COLUMN conversation_id SET NOT NULL;
ALTER TABLE copilot_context DROP CONSTRAINT copilot_context_pkey;
ALTER TABLE copilot_context ADD PRIMARY KEY (conversation_id);
CREATE INDEX copilot_context_exercise ON copilot_context(exercise_id);

ALTER TABLE copilot_compaction ADD COLUMN conversation_id uuid;
UPDATE copilot_compaction SET conversation_id=exercise_id;
ALTER TABLE copilot_compaction ALTER COLUMN conversation_id SET NOT NULL;
CREATE INDEX copilot_compaction_conversation ON copilot_compaction(conversation_id, sequence);
