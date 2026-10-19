\set ON_ERROR_STOP on
\getenv fixture_owner F3_CI_OWNER
CREATE SCHEMA pawbridge_f11_fixture;
CREATE TABLE pawbridge_f11_fixture.marker(value TEXT NOT NULL, owner TEXT NOT NULL);
INSERT INTO pawbridge_f11_fixture.marker VALUES ('pawbridge-f11-ci-only-v1', :'fixture_owner');
-- Extend only this run's existing writer after the exact F10 ACL passed.
GRANT UPDATE(content) ON pawbridge_community.comments TO pawbridge_f3_writer;
