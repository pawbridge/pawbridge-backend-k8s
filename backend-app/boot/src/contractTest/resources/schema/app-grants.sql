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
