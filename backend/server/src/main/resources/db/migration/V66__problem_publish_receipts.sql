CREATE TABLE problem_publish_receipt (
    actor_id uuid NOT NULL,
    request_key varchar(128) NOT NULL,
    fingerprint char(64) NOT NULL,
    PRIMARY KEY(actor_id, request_key)
);
