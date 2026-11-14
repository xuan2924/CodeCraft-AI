ALTER TABLE copilot_turn ADD COLUMN partial_text text NOT NULL DEFAULT '';
ALTER TABLE copilot_turn ADD COLUMN partial_sequence bigint NOT NULL DEFAULT 0 CHECK (partial_sequence >= 0);
