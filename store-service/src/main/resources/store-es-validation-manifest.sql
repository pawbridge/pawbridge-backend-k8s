-- Store ES 컷오버 검증용 MySQL canonical manifest.
-- 사용 예:
-- mysql --batch --raw --skip-column-names \
--   -h <host> -P <port> -u <user> -p pawbridge_store \
--   < store-es-validation-manifest.sql > /secure/path/store-products-v001.jsonl
-- manifest에는 비밀번호가 없지만 상품 데이터가 있으므로 공개 저장소에 커밋하지 않는다.

SET SESSION group_concat_max_len = 65535;

SELECT JSON_OBJECT(
    'skuId', s.id,
    'productId', p.id,
    'categoryId', p.category_id,
    'productName', p.name,
    'skuCode', s.sku_code,
    'optionName', COALESCE((
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
    'price', s.price,
    'stockQuantity', s.stock_quantity,
    'totalStockQuantity', (
        SELECT SUM(total_sku.stock_quantity)
        FROM product_skus total_sku
        WHERE total_sku.product_id = p.id
    ),
    'isPrimarySku', IF(
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
    'status', p.status,
    'imageUrl', p.image_url,
    'createdAt', DATE_FORMAT(p.created_at, '%Y-%m-%dT%H:%i:%s')
)
FROM product_skus s
JOIN products p ON p.id = s.product_id
ORDER BY s.id;
