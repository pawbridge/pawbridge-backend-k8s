-- Privileges of the single application role (pawbridge_app) in the contract tests.
--
-- Column lists are deliberate: REVOKE of a column privilege only has an effect when the role was granted
-- that column and not the whole table, and the permission contracts revoke single columns.
-- Add the grants a new contract needs here, with the narrowest list that lets the application work.

GRANT USAGE ON SCHEMA pawbridge_user TO pawbridge_app;
GRANT USAGE ON SCHEMA pawbridge_community TO pawbridge_app;

-- users: what the refresh rotation reads for its access-token claims. UPDATE on one column is the
-- minimum PostgreSQL requires for the SELECT ... FOR UPDATE row lock taken when a session is created.
-- DELETE backs the contract that deleting an account cascades to its sessions.
GRANT SELECT (user_id, nickname, role, care_reg_no, email, name) ON pawbridge_user.users TO pawbridge_app;
GRANT UPDATE (updated_at) ON pawbridge_user.users TO pawbridge_app;
GRANT DELETE ON pawbridge_user.users TO pawbridge_app;

GRANT SELECT (session_id, user_id, token_hash, rotated_at, created_at, last_used_at, expires_at,
              absolute_expires_at, revoked_at) ON pawbridge_user.auth_sessions TO pawbridge_app;
GRANT INSERT (user_id, token_hash, created_at, last_used_at, expires_at, absolute_expires_at)
    ON pawbridge_user.auth_sessions TO pawbridge_app;
GRANT UPDATE (token_hash, rotated_at, last_used_at, expires_at, revoked_at)
    ON pawbridge_user.auth_sessions TO pawbridge_app;
GRANT DELETE ON pawbridge_user.auth_sessions TO pawbridge_app;

GRANT SELECT (token_hash, session_id, rotated_at) ON pawbridge_user.auth_session_token_history TO pawbridge_app;
GRANT INSERT (token_hash, session_id, rotated_at) ON pawbridge_user.auth_session_token_history TO pawbridge_app;

-- Private notes (blocks, mailboxes, send, stream). Every list is the union of what the reader and the writer of the
-- packaged-JAR fixtures were granted, and nothing wider: the permission contracts revoke single columns and tables.
-- Column grants stay column-level on purpose, so that REVOKE SELECT(body) or REVOKE INSERT(body) takes effect.
GRANT SELECT (user_id) ON pawbridge_user.contact_deletions TO pawbridge_app;

GRANT SELECT (member_id), INSERT (member_id), UPDATE (member_id)
    ON pawbridge_community.private_note_members TO pawbridge_app;

GRANT SELECT (member_id, blocked_id, created_at), INSERT (member_id, blocked_id, created_at)
    ON pawbridge_community.private_note_blocks TO pawbridge_app;
GRANT DELETE ON pawbridge_community.private_note_blocks TO pawbridge_app;

GRANT SELECT (note_id, sender_id, recipient_id, body, context_type, context_id, created_at, expires_at)
    ON pawbridge_community.private_notes TO pawbridge_app;
GRANT INSERT (note_id, sender_id, recipient_id, body, reply_to, context_type, context_id, created_at, expires_at)
    ON pawbridge_community.private_notes TO pawbridge_app;
GRANT UPDATE (note_id) ON pawbridge_community.private_notes TO pawbridge_app;
GRANT DELETE ON pawbridge_community.private_notes TO pawbridge_app;

GRANT SELECT (member_id, note_id, direction, read_at, favorite, created_at)
    ON pawbridge_community.private_note_mailboxes TO pawbridge_app;
GRANT INSERT (member_id, note_id, direction, read_at, created_at)
    ON pawbridge_community.private_note_mailboxes TO pawbridge_app;
GRANT UPDATE (read_at, favorite) ON pawbridge_community.private_note_mailboxes TO pawbridge_app;
GRANT DELETE ON pawbridge_community.private_note_mailboxes TO pawbridge_app;

GRANT SELECT (sender_id, request_id, request_hash, note_id, created_at),
      INSERT (sender_id, request_id, request_hash, note_id, created_at)
    ON pawbridge_community.private_note_requests TO pawbridge_app;

-- The context check of a note that answers an animal report reads the author of that report.
GRANT SELECT (report_id, author_id, deleted_at) ON pawbridge_community.animal_reports TO pawbridge_app;
