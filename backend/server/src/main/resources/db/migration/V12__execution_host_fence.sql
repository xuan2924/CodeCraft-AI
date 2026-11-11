-- Bind each original dispatch to the Go boot generation observed before claim.
-- Previously dispatched tasks retain NULL and are only queried, never replayed.
ALTER TABLE workspace_test_task ADD COLUMN host_epoch text;
