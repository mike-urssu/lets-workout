-- Rows are added only by schema scripts; users never change this table (BR-009).
CREATE TABLE exercise
(
    id         uuid         NOT NULL PRIMARY KEY,
    name       varchar(100) NOT NULL,
    category   varchar(30)  NOT NULL,
    image_url  varchar(500),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_exercise_name ON exercise (name);
