ALTER TABLE problem_version ALTER COLUMN execution_image_digest DROP NOT NULL, ALTER COLUMN platform DROP NOT NULL;
ALTER TABLE problem_version ADD CONSTRAINT problem_execution_identity CHECK (
    CASE WHEN artifact->'manifest'->>'language'='PYTHON_PYTEST'
    THEN execution_image_digest IS NULL AND platform IS NULL
    ELSE execution_image_digest IS NOT NULL AND platform IS NOT NULL END
) NOT VALID;
ALTER TABLE problem_publish_receipt ADD COLUMN version_id uuid;
