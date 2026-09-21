CREATE TABLE product_search_documents (
    product_id BIGINT PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    sku_id BIGINT NOT NULL, source_name TEXT NOT NULL, source_option TEXT NOT NULL,
    analyzer_version VARCHAR(64) NOT NULL, tokens TSVECTOR NOT NULL
);
CREATE INDEX idx_product_search_tokens ON product_search_documents USING GIN(tokens);
CREATE INDEX idx_product_skus_primary ON product_skus(product_id,price,id);

-- Current representative SKU and stock always come from the source transaction snapshot.
CREATE VIEW product_search_source AS
SELECT p.id AS product_id,p.name,p.image_url,p.status,p.category_id,p.created_at,p.updated_at,
       sku.id AS sku_id,sku.price,
       (SELECT COALESCE(sum(stock_quantity),0) FROM product_skus all_skus WHERE all_skus.product_id=p.id) AS total_stock,
       COALESCE((SELECT string_agg(g.name||': '||v.name,', ' ORDER BY (g.name||': '||v.name) COLLATE "C")
          FROM sku_values sv JOIN option_values v ON v.id=sv.option_value_id
          JOIN option_groups g ON g.id=v.option_group_id WHERE sv.product_sku_id=sku.id),'') AS option_name
FROM products p CROSS JOIN LATERAL (
    SELECT id,price FROM product_skus WHERE product_id=p.id ORDER BY price,id LIMIT 1
) sku;
