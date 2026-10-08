CREATE TABLE users
(
    -- users.id is created by operator SQL, so the DB generates it (DEC-ARCH-012).
    id               uuid         NOT NULL DEFAULT uuidv7() PRIMARY KEY,
    login_id         varchar(100) NOT NULL,
    pin              varchar(6)   NOT NULL,
    failed_pin_count integer      NOT NULL DEFAULT 0,
    locked_until     timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    -- name.surname, duplicates numbered from 2 (BR-002)
    CONSTRAINT ck_users_login_id_format CHECK (login_id ~ '^[A-Za-z]+\.[A-Za-z]+([2-9]|[1-9][0-9]+)?$'),
    CONSTRAINT ck_users_pin_format CHECK (pin ~ '^[0-9]{6}$')
);

CREATE UNIQUE INDEX ux_users_login_id ON users (login_id);

CREATE TABLE login_session
(
    id           uuid        NOT NULL PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   varchar(64) NOT NULL,
    status       varchar(20) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz NOT NULL DEFAULT now(),
    ended_at     timestamptz,
    CONSTRAINT ck_login_session_status CHECK (status IN ('ACTIVE', 'REPLACED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_login_session_ended CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL) OR (status <> 'ACTIVE' AND ended_at IS NOT NULL))
);

CREATE UNIQUE INDEX ux_login_session_token_hash ON login_session (token_hash);
CREATE UNIQUE INDEX ux_login_session_user_active ON login_session (user_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_login_session_user ON login_session (user_id, ended_at);

-- Operators change PINs with SQL, bypassing the application; the DB ends the old login itself (BR-011, DEC-AUTH-008).
-- jOOQ's open-source DDL parser cannot read PL/pgSQL, so code generation skips it (D-TODO-ARCH-005).
-- [jooq ignore start]
CREATE FUNCTION revoke_login_on_pin_change() RETURNS trigger AS $$
BEGIN
    UPDATE login_session
       SET status = 'REVOKED', ended_at = now()
     WHERE user_id = NEW.id AND status = 'ACTIVE';
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_pin_revoke_login
    AFTER UPDATE OF pin ON users
    FOR EACH ROW
    WHEN (OLD.pin IS DISTINCT FROM NEW.pin)
EXECUTE FUNCTION revoke_login_on_pin_change();
-- [jooq ignore stop]
