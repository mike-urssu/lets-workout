-- Exercises become each user's own (requirements workout-exercise-manage, design 6.5).
-- The shared catalog stays as the template every account starts from; existing records move onto the copies.

ALTER TABLE exercise RENAME TO default_exercise;
ALTER INDEX ux_exercise_name RENAME TO ux_default_exercise_name;
ALTER INDEX ux_exercise_category_order RENAME TO ux_default_exercise_category_order;
ALTER TABLE default_exercise ALTER COLUMN name TYPE varchar(30);
ALTER TABLE default_exercise ALTER COLUMN name_en TYPE varchar(50);

CREATE TABLE exercise
(
    -- Rows from the trigger get a DB id; rows the application adds bring their own (DEC-ARCH-019).
    id                   uuid        NOT NULL DEFAULT uuidv7() PRIMARY KEY,
    user_id              uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    exercise_category_id uuid        NOT NULL REFERENCES exercise_category (id),
    name                 varchar(30) NOT NULL,
    name_en              varchar(50),
    target               varchar(50),
    sort_order           integer     NOT NULL, -- ties fall back to created_at, id (DEC-WORKOUT-025)
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    -- BR-023: the application trims; the DB refuses anything it would have trimmed
    CONSTRAINT ck_exercise_name CHECK (name <> '' AND name = trim(name)),
    CONSTRAINT ck_exercise_name_en CHECK (name_en IS NULL OR (name_en <> '' AND name_en = trim(name_en)))
);

-- [jooq ignore start]
-- BR-026: one name per user and body part, ignoring case. jOOQ's DDL parser cannot read expression indexes.
CREATE UNIQUE INDEX ux_exercise_user_category_name ON exercise (user_id, exercise_category_id, lower(name));

-- Functions keep the schema they were created in, whatever the caller's search path is.
CREATE FUNCTION copy_default_exercises(owner uuid) RETURNS void AS $$
    INSERT INTO exercise (user_id, exercise_category_id, name, name_en, target, sort_order)
    SELECT owner, exercise_category_id, name, name_en, target, sort_order FROM default_exercise;
$$ LANGUAGE sql SET search_path FROM CURRENT;

-- Existing users: copy the defaults, then point each session exercise at its owner's copy.
-- The reference still points at the renamed template, so it is replaced around the move; deleting an
-- exercise now deletes its records (BR-025). The inline constraint's generated name is PostgreSQL's.
SELECT copy_default_exercises(id) FROM users;
ALTER TABLE workout_session_exercise DROP CONSTRAINT workout_session_exercise_exercise_id_fkey;

UPDATE workout_session_exercise wse
   SET exercise_id = e.id
  FROM workout_session s, default_exercise d, exercise e
 WHERE s.id = wse.workout_session_id
   AND d.id = wse.exercise_id
   AND e.user_id = s.user_id
   AND e.exercise_category_id = d.exercise_category_id
   AND e.name = d.name;

ALTER TABLE workout_session_exercise
    ADD CONSTRAINT workout_session_exercise_exercise_id_fkey
        FOREIGN KEY (exercise_id) REFERENCES exercise (id) ON DELETE CASCADE;
-- [jooq ignore stop]

CREATE INDEX idx_workout_session_exercise_exercise ON workout_session_exercise (exercise_id);

-- BR-022: accounts are created by operators with SQL, so the DB hands out the defaults (DEC-WORKOUT-023).
-- [jooq ignore start]
CREATE FUNCTION copy_default_exercises_for_new_user() RETURNS trigger AS $$
BEGIN
    PERFORM copy_default_exercises(NEW.id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SET search_path FROM CURRENT;

CREATE TRIGGER trg_users_default_exercises
    AFTER INSERT ON users
    FOR EACH ROW
EXECUTE FUNCTION copy_default_exercises_for_new_user();
-- [jooq ignore stop]
