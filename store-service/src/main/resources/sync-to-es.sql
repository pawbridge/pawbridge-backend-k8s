-- =============================================
-- Store Service - 상품 검색 projection 전체 snapshot 발행
-- MySQL → Debezium CDC → store.product-sku.events → Elasticsearch
-- DB: pawbridge_store
-- =============================================
-- 실행 방법:
--   1. Store 쓰기를 중단하고 source/sink lag가 0인지 확인한다.
--   2. 새 version index와 store-products-write alias를 준비한다.
--   3. mysql -h <host> -P <port> -u <user> -p pawbridge_store < sync-to-es.sql
-- 이 SQL을 다시 실행해도 ES 문서 수는 skuId key 때문에 증가하지 않지만,
-- Outbox 행은 새로 쌓이므로 실행 횟수를 운영 기록에 남긴다.

SET SESSION group_concat_max_len = 65535;

START TRANSACTION;

INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at)
SELECT
    'product-sku'                       AS aggregate_type,
    CAST(s.id AS CHAR)                  AS aggregate_id,
    'SKU_UPDATED'                       AS event_type,
    JSON_OBJECT(
        'skuId',          s.id,
        'productId',      p.id,
        'categoryId',     p.category_id,
        'productName',    p.name,
        'skuCode',        s.sku_code,
        'optionName',     COALESCE((
            SELECT GROUP_CONCAT(
                CONCAT(og.name, ': ', ov.name)
                ORDER BY CONVERT(CONCAT(og.name, ': ', ov.name) USING utf8mb4) COLLATE utf8mb4_bin
                SEPARATOR ', '
            )
            FROM sku_values sv
            JOIN option_values ov ON ov.id = sv.option_value_id
            JOIN option_groups og ON og.id = ov.option_group_id
            WHERE sv.product_sku_id = s.id
        ), ''),
        'price',          s.price,
        'stockQuantity',  s.stock_quantity,
        'totalStockQuantity', (
            SELECT SUM(total_sku.stock_quantity)
            FROM product_skus total_sku
            WHERE total_sku.product_id = p.id
        ),
        'isPrimarySku',   IF(
            s.id = (
                SELECT primary_sku.id
                FROM product_skus primary_sku
                WHERE primary_sku.product_id = p.id
                ORDER BY primary_sku.price ASC, primary_sku.id ASC
                LIMIT 1
            ),
            JSON_EXTRACT('true', '$'),
            JSON_EXTRACT('false', '$')
        ),
        'status',         p.status,
        'imageUrl',       p.image_url,
        'createdAt',      DATE_FORMAT(p.created_at, '%Y-%m-%dT%H:%i:%s'),
        'updatedAt',      DATE_FORMAT(NOW(), '%Y-%m-%dT%H:%i:%s')
    )                                   AS payload,
    NOW()                               AS created_at
FROM product_skus s
JOIN products p ON s.product_id = p.id;

COMMIT;
