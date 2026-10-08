-- PostgreSQL 18 built-in uuidv7() replaces the hand-written uuid_v7() from V1 (DEC-ARCH-012).
ALTER TABLE users ALTER COLUMN id SET DEFAULT uuidv7();
DROP FUNCTION uuid_v7();
