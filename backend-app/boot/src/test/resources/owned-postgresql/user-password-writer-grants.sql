-- Explicit owner-applied increment after the existing F29 and LOCAL login grants; psql -v password_writer=ROLE.
\set ON_ERROR_STOP on
BEGIN;
GRANT SELECT(provider,password),UPDATE(password) ON TABLE pawbridge_user.users TO :"password_writer";
COMMIT;
