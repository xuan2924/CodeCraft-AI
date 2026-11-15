-- Per-dispatch price snapshots; never back-price historical calls using a newly observed tariff.
-- Raw legacy usage remains untouched. Missing normalized fields are explicitly incomplete in projections.
ALTER TABLE copilot_turn ADD COLUMN price_snapshot text;
ALTER TABLE copilot_turn ADD COLUMN usage_facts jsonb;
ALTER TABLE copilot_turn ADD COLUMN estimated_cost numeric CHECK (estimated_cost >= 0);
ALTER TABLE copilot_turn ADD COLUMN cost_currency varchar(3);
ALTER TABLE copilot_turn ADD COLUMN price_reason varchar(64);
ALTER TABLE copilot_turn ADD CHECK (estimated_cost IS NULL OR cost_currency IS NOT NULL);

ALTER TABLE copilot_compaction_call ADD COLUMN price_snapshot text;
ALTER TABLE copilot_compaction_call ADD COLUMN usage_facts jsonb;
ALTER TABLE copilot_compaction_call ADD COLUMN estimated_cost numeric CHECK (estimated_cost >= 0);
ALTER TABLE copilot_compaction_call ADD COLUMN cost_currency varchar(3);
ALTER TABLE copilot_compaction_call ADD COLUMN price_reason varchar(64);
ALTER TABLE copilot_compaction_call ADD CHECK (estimated_cost IS NULL OR cost_currency IS NOT NULL);

ALTER TABLE personal_model_check_request ADD COLUMN price_snapshot text;
ALTER TABLE personal_model_check_request ADD COLUMN configuration_payload text;
ALTER TABLE personal_model_check_request ADD COLUMN usage_payload text;
ALTER TABLE personal_model_check_request ADD COLUMN usage_facts jsonb;
ALTER TABLE personal_model_check_request ADD COLUMN estimated_cost numeric CHECK (estimated_cost >= 0);
ALTER TABLE personal_model_check_request ADD COLUMN cost_currency varchar(3);
ALTER TABLE personal_model_check_request ADD COLUMN price_reason varchar(64);
ALTER TABLE personal_model_check_request ADD CHECK (estimated_cost IS NULL OR cost_currency IS NOT NULL);
