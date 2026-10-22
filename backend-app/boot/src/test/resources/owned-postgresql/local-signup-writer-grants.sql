-- Explicit owner-applied increment after the existing F29, LOCAL login and user password grants; psql -v auth_writer=ROLE.
-- Sections are independent: apply only the ones whose feature flag will be enabled.
\set ON_ERROR_STOP on
BEGIN;
-- Signup duplicate check and password-reset lookup read email/provider (user_id, nickname are already granted).
GRANT SELECT(email,provider) ON TABLE pawbridge_user.users TO :"auth_writer";
-- Signup: pawbridge.local-signup.enabled
GRANT INSERT(email,name,nickname,password,role,care_reg_no,provider,provider_id,created_at,updated_at)
    ON TABLE pawbridge_user.users TO :"auth_writer";
-- Password reset: pawbridge.password-reset.enabled (updated_at is already granted by F13)
GRANT UPDATE(password) ON TABLE pawbridge_user.users TO :"auth_writer";
COMMIT;
