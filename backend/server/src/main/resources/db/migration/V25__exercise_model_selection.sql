ALTER TABLE workspace_exercise
    ADD COLUMN selected_connection_id uuid,
    ADD COLUMN selected_model_id uuid,
    ADD COLUMN selected_connection_version bigint,
    ADD COLUMN selected_model_version bigint,
    ADD CONSTRAINT exercise_model_selection_complete CHECK (
        (selected_connection_id IS NULL AND selected_model_id IS NULL AND selected_connection_version IS NULL AND selected_model_version IS NULL)
        OR (selected_connection_id IS NOT NULL AND selected_model_id IS NOT NULL AND selected_connection_version IS NOT NULL AND selected_model_version IS NOT NULL AND selected_connection_version > 0 AND selected_model_version > 0)
    );
