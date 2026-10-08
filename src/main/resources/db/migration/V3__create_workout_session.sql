CREATE TABLE workout_session
(
    id             uuid         NOT NULL PRIMARY KEY,
    user_id        uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status         varchar(20)  NOT NULL,
    performed_date date         NOT NULL,
    started_at     timestamptz  NOT NULL,
    ended_at       timestamptz,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_workout_session_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    -- BR-008
    CONSTRAINT ck_workout_session_ended CHECK (
        (status = 'IN_PROGRESS' AND ended_at IS NULL)
            OR (status = 'COMPLETED' AND ended_at IS NOT NULL AND ended_at >= started_at))
);

-- BR-011, also under concurrent starts
CREATE UNIQUE INDEX ux_workout_session_user_in_progress ON workout_session (user_id) WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_workout_session_user_performed
    ON workout_session (user_id, performed_date DESC, started_at DESC, id DESC);

CREATE TABLE workout_session_exercise
(
    id                 uuid        NOT NULL PRIMARY KEY,
    workout_session_id uuid        NOT NULL REFERENCES workout_session (id) ON DELETE CASCADE,
    exercise_id        uuid        NOT NULL REFERENCES exercise (id),
    created_at         timestamptz NOT NULL DEFAULT now()
);

-- REQ-EXERCISE-001: an exercise is added to a session once
CREATE UNIQUE INDEX ux_workout_session_exercise_session_exercise ON workout_session_exercise (workout_session_id, exercise_id);
CREATE INDEX idx_workout_session_exercise_session ON workout_session_exercise (workout_session_id, created_at, id);

CREATE TABLE workout_set
(
    id                          uuid          NOT NULL PRIMARY KEY,
    workout_session_exercise_id uuid          NOT NULL REFERENCES workout_session_exercise (id) ON DELETE CASCADE,
    weight                      numeric(6, 2) NOT NULL,
    repetitions                 integer       NOT NULL,
    created_at                  timestamptz   NOT NULL DEFAULT now(),
    updated_at                  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_workout_set_weight CHECK (weight >= 0 AND weight <= 1000),           -- BR-005
    CONSTRAINT ck_workout_set_repetitions CHECK (repetitions >= 1 AND repetitions <= 1000) -- BR-003
);

CREATE INDEX idx_workout_set_session_exercise ON workout_set (workout_session_exercise_id, created_at, id);
