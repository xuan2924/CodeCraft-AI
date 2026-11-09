CREATE TABLE identity_revocation_intent (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES identity_user(id),
    target_id uuid NOT NULL REFERENCES identity_user(id),
    actor_session_hash varchar(64) NOT NULL,
    scope_session_hash varchar(64),
    kind varchar(32) NOT NULL,
    request_key varchar(128) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    preparation_ids uuid[] NOT NULL,
    last_checked_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'FENCED', 'SUCCEEDED', 'REJECTED')),
    created_at timestamptz NOT NULL,
    UNIQUE(actor_id, kind, target_id, request_key)
);
CREATE UNIQUE INDEX one_pending_revocation_per_user ON identity_revocation_intent(target_id)
    WHERE state IN ('PENDING', 'FENCED');
ALTER TABLE workspace_exercise ADD COLUMN preparation_session_hash varchar(64);
ALTER TABLE workspace_exercise ADD COLUMN preparation_not_after timestamptz;
