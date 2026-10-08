-- Workout proof photos and videos (docs/design/workout-media.md 6.2). Files live in object storage under
-- users/{userId}/workout-sessions/{sessionId}/media/{id}/; rows of an in-progress session are staged uploads
-- and get sort_order when the session completes (DEC-MEDIA-001).
CREATE TABLE workout_media
(
    id                 uuid        NOT NULL PRIMARY KEY,
    workout_session_id uuid        NOT NULL REFERENCES workout_session (id) ON DELETE CASCADE,
    media_type         varchar(10) NOT NULL,
    content_type       varchar(30) NOT NULL,
    file_size          integer     NOT NULL,
    sort_order         integer,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_workout_media_media_type CHECK (media_type IN ('PHOTO', 'VIDEO')),
    -- BR-006
    CONSTRAINT ck_workout_media_content_type CHECK (content_type IN
                                                    ('image/jpeg', 'image/png', 'image/heic', 'video/mp4', 'video/quicktime')),
    CONSTRAINT ck_workout_media_type CHECK ((media_type = 'PHOTO' AND content_type LIKE 'image/%')
        OR (media_type = 'VIDEO' AND content_type LIKE 'video/%')),
    -- BR-005
    CONSTRAINT ck_workout_media_file_size CHECK (file_size BETWEEN 1 AND 104857600),
    CONSTRAINT ck_workout_media_sort_order CHECK (sort_order BETWEEN 1 AND 10)
);

-- BR-003; rows without sort_order (staged uploads) never collide
CREATE UNIQUE INDEX ux_workout_media_session_order ON workout_media (workout_session_id, sort_order);
