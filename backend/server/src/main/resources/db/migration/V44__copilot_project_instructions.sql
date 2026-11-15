-- The exact guidance supplied to each model request shares the turn's existing accounting and retention.
ALTER TABLE copilot_turn ADD COLUMN project_instructions text;
