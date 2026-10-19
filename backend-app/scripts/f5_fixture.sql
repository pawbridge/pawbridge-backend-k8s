\set ON_ERROR_STOP on
\getenv fixture_owner F3_CI_OWNER
CREATE SCHEMA pawbridge_f5_fixture;
CREATE TABLE pawbridge_f5_fixture.marker(value TEXT NOT NULL, owner TEXT NOT NULL);
INSERT INTO pawbridge_f5_fixture.marker VALUES ('pawbridge-f5-ci-only-v1', :'fixture_owner');
-- Existing writer pool/role; identity, author, board and creation time remain immutable to this role.
GRANT UPDATE(title,content,image_urls) ON pawbridge_community.posts TO pawbridge_f3_writer;
