-- Preserve the existing MySQL character repertoire during the rollback window.
-- Source: live information_schema.columns checked on 2026-09-21; exact inventory
-- and isolated coverage are in infrastructure/migration-tests/rollback-charset-columns.json.
-- JSON and utf8mb4 columns remain unrestricted. Flyway history is not reverse-copied;
-- Spring Batch sequence tables become PG sequences and have no text payload.
-- Use C collation so regexp ranges follow code points, not locale sort order.
-- New validated CHECKs fail migration/import on incompatible existing values.
-- Remove these named constraints only through a reviewed follow-up migration after
-- ending the rollback window (or validating the rollback target charset upgrade).

ALTER TABLE categories ADD CONSTRAINT ck_rollback_charset_categories
    CHECK (description COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND name COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE option_groups ADD CONSTRAINT ck_rollback_charset_option_groups
    CHECK (name COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE option_values ADD CONSTRAINT ck_rollback_charset_option_values
    CHECK (name COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE order_items ADD CONSTRAINT ck_rollback_charset_order_items
    CHECK (product_name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND sku_code COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE orders ADD CONSTRAINT ck_rollback_charset_orders
    CHECK (delivery_address COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND delivery_message COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND delivery_status COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND order_uuid COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND receiver_name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND receiver_phone COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND status COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE outbox ADD CONSTRAINT ck_rollback_charset_outbox
    CHECK (aggregate_id COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND aggregate_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND event_type COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE product_skus ADD CONSTRAINT ck_rollback_charset_product_skus
    CHECK (sku_code COLLATE "C" !~ U&'[\+010000-\+10FFFF]');

ALTER TABLE products ADD CONSTRAINT ck_rollback_charset_products
    CHECK (description COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND image_url COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND name COLLATE "C" !~ U&'[\+010000-\+10FFFF]'
       AND status COLLATE "C" !~ U&'[\+010000-\+10FFFF]');
