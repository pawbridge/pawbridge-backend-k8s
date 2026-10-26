-- Preserve the existing MySQL character repertoire during the rollback window.
-- Source: live information_schema.columns checked on 2026-09-21; exact inventory
-- and isolated coverage are in infrastructure/migration-tests/rollback-charset-columns.json.
-- JSON and utf8mb4 columns remain unrestricted. Flyway history is not reverse-copied;
-- Spring Batch sequence tables become PG sequences and have no text payload.
-- Use C collation so regexp ranges follow code points, not locale sort order.
-- New validated CHECKs fail migration/import on incompatible existing values.
-- Remove these named constraints only through a reviewed follow-up migration after
-- ending the rollback window (or validating the rollback target charset upgrade).

ALTER TABLE comments ADD CONSTRAINT ck_rollback_charset_comments
    CHECK (content COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE outbox_events ADD CONSTRAINT ck_rollback_charset_outbox_events
    CHECK (aggregate_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND aggregate_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND event_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND type COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE posts ADD CONSTRAINT ck_rollback_charset_posts
    CHECK (board_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND content COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND title COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE processed_events ADD CONSTRAINT ck_rollback_charset_processed_events
    CHECK (event_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND event_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]');
