\set ON_ERROR_STOP on
\getenv fixture_owner F3_CI_OWNER
CREATE SCHEMA pawbridge_f4_fixture;
CREATE TABLE pawbridge_f4_fixture.marker(value TEXT NOT NULL, owner TEXT NOT NULL);
INSERT INTO pawbridge_f4_fixture.marker VALUES ('pawbridge-f4-ci-only-v1', :'fixture_owner');
-- Preserve F3's title UPDATE and physical posts DELETE denial.
GRANT UPDATE(deleted_at,updated_at) ON pawbridge_community.posts TO pawbridge_f3_writer;
GRANT DELETE ON pawbridge_community.post_search_documents TO pawbridge_f3_writer;
