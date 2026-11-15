ALTER TABLE copilot_skill_snapshot ADD COLUMN explicitly_selected boolean NOT NULL DEFAULT false;
ALTER TABLE copilot_skill_snapshot ADD COLUMN explicit_loaded_at timestamptz;
