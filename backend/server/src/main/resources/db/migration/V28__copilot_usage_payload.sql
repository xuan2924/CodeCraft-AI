ALTER TABLE copilot_turn ADD COLUMN usage_payload text CHECK (octet_length(usage_payload) <= 32768);
ALTER TABLE copilot_turn ADD COLUMN configuration_payload text NOT NULL DEFAULT '{}' CHECK (octet_length(configuration_payload) <= 32768);
