ALTER TABLE copilot_task ADD COLUMN file_observations jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE copilot_turn ADD COLUMN baseline_input_tokens bigint;
