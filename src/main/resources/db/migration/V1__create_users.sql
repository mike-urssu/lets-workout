-- UUIDv7 (RFC 9562) generator usable on any PostgreSQL version; users.id is created by operator SQL (DEC-ARCH-012).
CREATE FUNCTION uuid_v7() RETURNS uuid AS $$
SELECT encode(
           set_bit(
               set_bit(
                   overlay(uuid_send(gen_random_uuid())
                           PLACING substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
                           FROM 1 FOR 6),
                   52, 1),
               53, 1),
           'hex')::uuid
$$ LANGUAGE sql VOLATILE;

CREATE TABLE users
(
    id               uuid         NOT NULL DEFAULT uuid_v7() PRIMARY KEY,
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
