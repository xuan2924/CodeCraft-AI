-- Retired presets no longer have application adapters. Preserve the user's
-- endpoint/model/key as CUSTOM, but invalidate checks and require explicit
-- selection of the new configuration revision before another model dispatch.
UPDATE personal_model_connection
SET preset = 'CUSTOM', version = version + 1,
    configuration_version = configuration_version + 1, updated_at = CURRENT_TIMESTAMP
WHERE preset IN ('GLM', 'KIMI', 'OPENAI');

-- Platform definitions embed the same enum, including published/retired versions
-- retained by exercise bindings. Invalidate pre-retirement checks without changing
-- encrypted credentials, instructions, model identity, or those bindings.
UPDATE platform_agent_version
SET definition = jsonb_set(definition, '{connection,preset}', '"CUSTOM"'::jsonb),
    revision = revision + 1, credential_revision = credential_revision + 1
WHERE definition #>> '{connection,preset}' IN ('GLM', 'KIMI', 'OPENAI');

ALTER TABLE personal_model_connection DROP CONSTRAINT personal_model_connection_preset_check;
ALTER TABLE personal_model_connection ADD CONSTRAINT personal_model_connection_preset_check
    CHECK (preset IN ('DEEPSEEK', 'CUSTOM'));
