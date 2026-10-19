\set ON_ERROR_STOP on
\getenv fixture_owner F3_CI_OWNER
BEGIN;
CREATE SCHEMA pawbridge_f13_fixture;
CREATE TABLE pawbridge_f13_fixture.marker(value TEXT NOT NULL, owner TEXT NOT NULL, charset_definition TEXT);
INSERT INTO pawbridge_f13_fixture.marker(value,owner) VALUES ('pawbridge-f13-ci-only-v1', :'fixture_owner');
ALTER TABLE pawbridge_user.users ADD CONSTRAINT users_nickname_key UNIQUE (nickname);
CREATE UNIQUE INDEX uk_users_nickname_folded ON pawbridge_user.users (lower(nickname));
ALTER TABLE pawbridge_user.users ADD CONSTRAINT ck_rollback_charset_users
    CHECK (care_reg_no COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND email COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND nickname COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND password COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND provider COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND provider_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND role COLLATE "C" !~ U&'[\+010000-\+10FFFF]');
UPDATE pawbridge_f13_fixture.marker SET charset_definition=(
 SELECT pg_get_constraintdef(oid) FROM pg_constraint
 WHERE conrelid='pawbridge_user.users'::regclass AND conname='ck_rollback_charset_users');
ALTER TABLE pawbridge_f13_fixture.marker ALTER COLUMN charset_definition SET NOT NULL;
GRANT USAGE ON SCHEMA pawbridge_user TO pawbridge_f3_writer;
GRANT SELECT(user_id,nickname),UPDATE(nickname,updated_at) ON pawbridge_user.users TO pawbridge_f3_writer;
COMMIT;
