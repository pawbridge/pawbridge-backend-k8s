\set ON_ERROR_STOP on
\getenv fixture_owner F3_CI_OWNER
CREATE SCHEMA pawbridge_f9_fixture;
CREATE TABLE pawbridge_f9_fixture.marker(value TEXT NOT NULL, owner TEXT NOT NULL);
INSERT INTO pawbridge_f9_fixture.marker VALUES ('pawbridge-f9-ci-only-v1', :'fixture_owner');
-- Exact comments constraint from the versioned V3 rollback-window migration.
ALTER TABLE pawbridge_community.comments ADD CONSTRAINT ck_rollback_charset_comments
 CHECK (content COLLATE "C" !~ U&'[\+010000-\+10FFFF]');
-- Same writer/pool, with only the fields needed for INSERT ... RETURNING.
GRANT INSERT(post_id,author_id,content,created_at,updated_at)
 ON pawbridge_community.comments TO pawbridge_f3_writer;
GRANT SELECT(comment_id) ON pawbridge_community.comments TO pawbridge_f3_writer;
-- PostgreSQL identity allocation needs no separate sequence privileges.
