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
