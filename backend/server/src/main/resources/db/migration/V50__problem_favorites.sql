ALTER TABLE problem_version ADD COLUMN available boolean NOT NULL DEFAULT true;
CREATE TABLE problem_favorite (
    user_id uuid NOT NULL,
    problem_id text NOT NULL,
    title text NOT NULL,
    PRIMARY KEY(user_id, problem_id)
);
