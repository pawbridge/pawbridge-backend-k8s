-- Explicit owner-applied increment after the existing auth-session grants; psql -v login_writer=ROLE.
\set ON_ERROR_STOP on
BEGIN;
GRANT SELECT(password) ON TABLE pawbridge_user.users TO :"login_writer";
GRANT INSERT(token,user_id,expires_at,created_at) ON TABLE pawbridge_user.refresh_tokens TO :"login_writer";
COMMIT;
