\set ON_ERROR_STOP on
\getenv fixture_owner F3_CI_OWNER
BEGIN;
CREATE SCHEMA pawbridge_f17_fixture;
CREATE TABLE pawbridge_f17_fixture.marker(value TEXT NOT NULL, owner TEXT NOT NULL);
INSERT INTO pawbridge_f17_fixture.marker VALUES ('pawbridge-f17-ci-only-v1', :'fixture_owner');
CREATE TABLE pawbridge_user.contact_deletions (
    user_id BIGINT PRIMARY KEY REFERENCES pawbridge_user.users(user_id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE pawbridge_community.private_note_members (
    member_id BIGINT PRIMARY KEY CHECK (member_id > 0)
);
CREATE TABLE pawbridge_community.private_note_blocks (
    member_id BIGINT NOT NULL REFERENCES pawbridge_community.private_note_members(member_id) ON DELETE CASCADE,
    blocked_id BIGINT NOT NULL REFERENCES pawbridge_community.private_note_members(member_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (member_id, blocked_id),
    CHECK (member_id <> blocked_id)
);
CREATE INDEX idx_private_note_blocked_member ON pawbridge_community.private_note_blocks (blocked_id);
GRANT SELECT(user_id) ON pawbridge_user.contact_deletions TO pawbridge_f2_reader;
GRANT SELECT(member_id,blocked_id) ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader;
COMMIT;
