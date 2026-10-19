\set ON_ERROR_STOP on
\getenv fixture_owner F3_CI_OWNER
CREATE SCHEMA pawbridge_f10_fixture;
CREATE TABLE pawbridge_f10_fixture.marker(value TEXT NOT NULL, owner TEXT NOT NULL);
INSERT INTO pawbridge_f10_fixture.marker VALUES ('pawbridge-f10-ci-only-v1', :'fixture_owner');
-- Extend the same writer only after all F3-F9 checks passed with their original ACL.
GRANT SELECT(author_id,deleted_at) ON pawbridge_community.comments TO pawbridge_f3_writer;
GRANT UPDATE(deleted_at,updated_at) ON pawbridge_community.comments TO pawbridge_f3_writer;
