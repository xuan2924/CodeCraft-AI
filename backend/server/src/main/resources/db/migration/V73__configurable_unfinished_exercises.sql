-- Creation is serialized by the identity_user row lock held by authorizeMutation.
DROP INDEX one_unfinished_exercise_per_user;
CREATE INDEX unfinished_exercises_by_user ON workspace_exercise(user_id, created_at DESC, id DESC) WHERE state <> 'ENDED';
