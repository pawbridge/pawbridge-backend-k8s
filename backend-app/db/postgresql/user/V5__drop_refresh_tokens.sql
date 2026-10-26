-- Legacy JWT refresh tokens. Login and refresh use auth_sessions (V4); the release that ships this file
-- also removes the last statement touching refresh_tokens (member deletion), so once that release runs
-- no build reads or writes the table and its rows can never be used again.
-- Deploy order: roll out the application without the refresh_tokens reference first, then apply V5.
-- An older build still running after V5 would fail every member deletion.
-- The plain DROP (RESTRICT) also removes the identity sequence, the unique token index and V2's
-- ck_rollback_charset_refresh_tokens; any unexpected dependent object aborts the migration instead.
DROP TABLE refresh_tokens;
