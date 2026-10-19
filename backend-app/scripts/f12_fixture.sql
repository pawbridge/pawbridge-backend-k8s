\set ON_ERROR_STOP on
\getenv fixture_owner F3_CI_OWNER
BEGIN;
CREATE SCHEMA pawbridge_f12_fixture;
CREATE TABLE pawbridge_f12_fixture.marker(value TEXT NOT NULL, owner TEXT NOT NULL);
INSERT INTO pawbridge_f12_fixture.marker VALUES ('pawbridge-f12-ci-only-v1', :'fixture_owner');
ALTER TABLE pawbridge_user.users
 ADD COLUMN email VARCHAR(255), ADD COLUMN name VARCHAR(20),
 ADD COLUMN provider VARCHAR(20), ADD COLUMN role VARCHAR(20),
 ADD COLUMN created_at TIMESTAMP(6), ADD COLUMN password VARCHAR(255),
 ADD COLUMN care_reg_no VARCHAR(50), ADD COLUMN provider_id VARCHAR(100),
 ADD COLUMN updated_at TIMESTAMP(6);
-- Only the new fields in this run-owned two-column fixture are backfilled.
UPDATE pawbridge_user.users SET
 email='f12-synthetic-' || user_id || '@fixture.invalid',
 name='F12-' || left(user_id::text,16), provider='LOCAL',
 role=CASE mod(user_id,3) WHEN 0 THEN 'ROLE_ADMIN' WHEN 1 THEN 'ROLE_SHELTER' ELSE 'ROLE_USER' END;
ALTER TABLE pawbridge_user.users
 ALTER COLUMN email SET NOT NULL, ALTER COLUMN name SET NOT NULL,
 ALTER COLUMN provider SET NOT NULL, ALTER COLUMN role SET NOT NULL,
 ADD CONSTRAINT ck_f12_users_role CHECK (role IN ('ROLE_ADMIN','ROLE_SHELTER','ROLE_USER'));
COMMIT;
