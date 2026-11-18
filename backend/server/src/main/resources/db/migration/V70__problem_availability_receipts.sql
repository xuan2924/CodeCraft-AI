CREATE TABLE problem_availability_receipt (
    actor_id uuid NOT NULL,
    request_key varchar(128) NOT NULL,
    version_id uuid NOT NULL,
    available boolean NOT NULL,
    PRIMARY KEY(actor_id, request_key)
);
