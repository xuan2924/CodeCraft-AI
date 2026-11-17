-- Terminal sessions reuse the command's durable execution, quota and lifecycle owner.
ALTER TABLE workspace_command
    ADD COLUMN terminal boolean NOT NULL DEFAULT false,
    ADD COLUMN terminal_ticket_hash varchar(64),
    ADD COLUMN terminal_ticket_expires_at timestamptz,
    ADD COLUMN terminal_ticket_consumed boolean NOT NULL DEFAULT false,
    ADD COLUMN terminal_opened_at timestamptz,
    ADD COLUMN terminal_closed_at timestamptz,
    ADD CONSTRAINT terminal_ticket_hash_format CHECK (terminal_ticket_hash IS NULL OR terminal_ticket_hash ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX workspace_terminal_ticket_unique ON workspace_command(terminal_ticket_hash) WHERE terminal_ticket_hash IS NOT NULL;
CREATE INDEX workspace_terminal_unattached_expiry ON workspace_command(terminal_ticket_expires_at)
    WHERE terminal AND NOT quiescent AND NOT terminal_ticket_consumed;
