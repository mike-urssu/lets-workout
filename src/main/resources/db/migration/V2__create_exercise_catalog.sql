-- Reference data: rows come only from schema scripts and users never change them (BR-009).
-- Ids are made by the database because no application code inserts these rows (DEC-ARCH-018).
CREATE TABLE exercise_category
(
    id         uuid         NOT NULL DEFAULT uuidv7() PRIMARY KEY,
    name       varchar(20)  NOT NULL,
    image_url  varchar(300) NOT NULL,
    sort_order integer      NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_exercise_category_name ON exercise_category (name);
CREATE UNIQUE INDEX ux_exercise_category_sort_order ON exercise_category (sort_order);

CREATE TABLE exercise
(
    id                   uuid         NOT NULL DEFAULT uuidv7() PRIMARY KEY,
    exercise_category_id uuid         NOT NULL REFERENCES exercise_category (id),
    name                 varchar(100) NOT NULL,
    name_en              varchar(100) NOT NULL,
    target               varchar(50)  NOT NULL,
    sort_order           integer      NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_exercise_name ON exercise (name);
CREATE UNIQUE INDEX ux_exercise_category_order ON exercise (exercise_category_id, sort_order);

-- Initial catalog: requirements workout-record appendix A (TODO-014).
-- [jooq ignore start]
INSERT INTO exercise_category (name, image_url, sort_order)
VALUES ('가슴', '/images/exercise-categories/chest.jpg', 1),
       ('등', '/images/exercise-categories/back.jpg', 2),
       ('어깨', '/images/exercise-categories/shoulders.jpg', 3),
       ('하체', '/images/exercise-categories/legs.jpg', 4);

INSERT INTO exercise (exercise_category_id, name, name_en, target, sort_order)
SELECT c.id, e.name, e.name_en, e.target, e.sort_order
FROM (VALUES ('가슴', 1, '벤치프레스', 'Bench Press', '가슴 중부 타겟'),
             ('가슴', 2, '스미스 벤치프레스', 'Smith Machine Bench Press', '가슴 중부 타겟'),
             ('가슴', 3, '인클라인 벤치프레스', 'Incline Bench Press', '가슴 상부 타겟'),
             ('가슴', 4, '스미스 인클라인 벤치프레스', 'Smith Machine Incline Bench Press', '가슴 상부 타겟'),
             ('가슴', 5, '펙덱 플라이', 'Pec Deck Fly', '가슴 안쪽 타겟'),
             ('등', 1, '데드리프트', 'Deadlift', '등 하부 타겟'),
             ('등', 2, '랫풀다운', 'Lat Pulldown', '광배근 타겟'),
             ('등', 3, '바벨 로우', 'Barbell Row', '등 전체 타겟'),
             ('등', 4, '시티드 로우', 'Seated Row', '등 중부 타겟'),
             ('등', 5, '와이드 풀다운 리어', 'Wide Pulldown Rear', '등 상부 타겟'),
             ('등', 6, '암풀다운', 'Straight-arm Pulldown', '광배근 타겟'),
             ('어깨', 1, '숄더 프레스', 'Shoulder Press', '어깨 전면 타겟'),
             ('어깨', 2, '바벨 숄더 프레스', 'Barbell Shoulder Press', '어깨 전면 타겟'),
             ('어깨', 3, '덤벨 숄더 프레스', 'Dumbbell Shoulder Press', '어깨 전면 타겟'),
             ('어깨', 4, '사이드 레터럴 레이즈', 'Side Lateral Raise', '어깨 측면 타겟'),
             ('어깨', 5, '벤트 오버 레터럴 레이즈', 'Bent-over Lateral Raise', '어깨 후면 타겟'),
             ('하체', 1, '스쿼트', 'Squat', '대퇴사두 타겟'),
             ('하체', 2, '레그 프레스', 'Leg Press', '대퇴사두 타겟'),
             ('하체', 3, '레그 익스텐션', 'Leg Extension', '대퇴사두 타겟'),
             ('하체', 4, '레그 컬', 'Leg Curl', '햄스트링 타겟'),
             ('하체', 5, '런지', 'Lunge', '대퇴사두·둔근 타겟'),
             ('하체', 6, '힙 어덕션', 'Hip Adduction', '허벅지 안쪽 타겟'),
             ('하체', 7, '힙 어브덕션', 'Hip Abduction', '엉덩이 바깥쪽 타겟'),
             ('하체', 8, '힙 쓰러스트', 'Hip Thrust', '둔근 타겟')) AS e (category, sort_order, name, name_en, target)
         JOIN exercise_category c ON c.name = e.category;
-- [jooq ignore stop]
