package com.pawbridge.backend.store.internal.catalog.infrastructure

import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.TransactionOperations
import java.time.LocalDateTime
import java.util.Locale

/** Explicitly assembled by Boot with the Store reader and a read-only transaction template. */
class JdbcStoreCatalog(
    private val jdbc: NamedParameterJdbcOperations,
    private val transactions: TransactionOperations,
) : StoreCatalog, AutoCloseable {

    private val terms = KoreanSearchTerms()

    override fun search(request: StoreCatalog.Search): StoreCatalog.SearchResult {
        val page = request.page ?: 0
        val size = request.size ?: 20
        if (page < 0 || size < 1 || size > 100 || page.toLong() * size > 100_000L) {
            throw IllegalArgumentException("검색 페이지 범위를 확인해 주세요.")
        }
        val minPrice = request.minPrice
        val maxPrice = request.maxPrice
        if (minPrice != null && minPrice < 0 || maxPrice != null && maxPrice < 0 ||
            minPrice != null && maxPrice != null && minPrice > maxPrice
        ) {
            throw IllegalArgumentException("가격 범위를 확인해 주세요.")
        }
        val order = when (request.sortBy ?: "skuId") {
            "skuId" -> "sku_id"
            "price", "minPrice" -> "price"
            "createdAt" -> "created_at"
            else -> throw IllegalArgumentException("지원하지 않는 정렬입니다.")
        }
        val direction = request.sortOrder ?: "desc"
        if (!direction.equals("asc", ignoreCase = true) && !direction.equals("desc", ignoreCase = true)) {
            throw IllegalArgumentException("지원하지 않는 정렬 방향입니다.")
        }
        val keyword = request.keyword
        val query = terms.query(keyword)
        // Java String.isBlank semantics (Character.isWhitespace); Kotlin's isBlank also treats NBSP as blank.
        if (query.isEmpty() && keyword != null && !keyword.all { Character.isWhitespace(it) }) {
            return response(listOf(), 0, page, size)
        }
        val params = HashMap<String, Any>(
            mapOf(
                "offset" to page.toLong() * size,
                "size" to size,
                "query" to query,
                "version" to KoreanSearchTerms.VERSION,
            ),
        )
        val filters = arrayListOf("s.status='ACTIVE'")
        if (request.categoryId != null) {
            filters.add("s.category_id=:category")
            params["category"] = request.categoryId
        }
        if (minPrice != null) {
            filters.add("s.price>=:min")
            params["min"] = minPrice
        }
        if (maxPrice != null) {
            filters.add("s.price<=:max")
            params["max"] = maxPrice
        }
        if (request.inStockOnly == true) {
            filters.add("s.total_stock>0")
        }
        if (query.isNotEmpty()) {
            filters.add("d.tokens @@ to_tsquery('simple',:query)")
        }
        val readiness = if (query.isEmpty()) "true" else READINESS
        val sort = order + " " + direction.uppercase(Locale.ROOT) + " NULLS LAST,sku_id ASC"
        // Only fixed fragments enter SQL; user values remain named parameters. This is the original Store CTE.
        val sql = "WITH readiness AS (SELECT " + readiness + " AS ready), matched AS (" +
            "SELECT s.* FROM pawbridge_store.product_search_source s LEFT JOIN pawbridge_store.product_search_documents d ON d.product_id=s.product_id WHERE " +
            filters.joinToString(" AND ") + "), total AS (SELECT count(*) AS total_count FROM matched), sliced AS (" +
            "SELECT * FROM matched ORDER BY " + sort + " LIMIT :size OFFSET :offset) " +
            "SELECT readiness.ready,total.total_count,sliced.* FROM readiness CROSS JOIN total LEFT JOIN sliced ON readiness.ready ORDER BY " + sort
        try {
            return transactions.execute {
                jdbc.query(
                    sql,
                    params,
                    ResultSetExtractor { rs ->
                        val items = ArrayList<StoreCatalog.Item>()
                        var count = 0L
                        while (rs.next()) {
                            if (!rs.getBoolean("ready")) {
                                throw CatalogSearchUnavailableException()
                            }
                            count = rs.getLong("total_count")
                            if (rs.getObject("product_id") == null) {
                                continue
                            }
                            items.add(
                                StoreCatalog.Item(
                                    rs.getLong("product_id"),
                                    rs.getLong("sku_id"),
                                    rs.getString("name"),
                                    rs.getString("option_name"),
                                    rs.getString("name"),
                                    rs.getString("image_url"),
                                    rs.getLong("price"),
                                    Math.toIntExact(rs.getLong("total_stock")),
                                    rs.getString("status"),
                                    rs.getObject("created_at", LocalDateTime::class.java),
                                    rs.getObject("updated_at", LocalDateTime::class.java),
                                ),
                            )
                        }
                        response(items, count, page, size)
                    },
                )
            }!!
        } catch (failure: DataAccessException) {
            throw CatalogSearchUnavailableException(failure)
        } catch (failure: CannotCreateTransactionException) {
            throw CatalogSearchUnavailableException(failure)
        } catch (failure: TransactionTimedOutException) {
            throw CatalogSearchUnavailableException(failure)
        }
    }

    private fun response(items: List<StoreCatalog.Item>, count: Long, page: Int, size: Int): StoreCatalog.SearchResult {
        val pages = Math.toIntExact((count + size - 1) / size)
        return StoreCatalog.SearchResult(items, count, page, pages, page.toLong() + 1 < pages)
    }

    override fun product(productId: Long): StoreCatalog.Product =
        transactions.execute {
            val products: List<StoreCatalog.Product> = jdbc.query(
                PRODUCT,
                mapOf("id" to productId),
                RowMapper { rs, _ ->
                    StoreCatalog.Product(
                        rs.getLong("product_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("image_url"),
                        rs.getString("status"),
                        rs.getObject("category_id", Long::class.javaObjectType),
                        rs.getString("category_name"),
                        rs.getObject("view_count", Long::class.javaObjectType),
                        rs.getObject("created_at", LocalDateTime::class.java),
                        rs.getObject("updated_at", LocalDateTime::class.java),
                        listOf(),
                    )
                },
            )
            if (products.isEmpty()) {
                throw IllegalArgumentException("Product not found: $productId")
            }
            val product = products.first()
            val skus: List<StoreCatalog.Sku> = jdbc.query(
                SKUS,
                mapOf("id" to productId),
                ResultSetExtractor { rs ->
                    val result = LinkedHashMap<Long, StoreCatalog.Sku>()
                    while (rs.next()) {
                        val id = rs.getLong("sku_id")
                        var sku = result[id]
                        if (sku == null) {
                            sku = StoreCatalog.Sku(
                                id,
                                rs.getString("sku_code"),
                                rs.getLong("price"),
                                rs.getInt("stock_quantity"),
                                HashMap<String, String>(),
                            )
                            result[id] = sku
                        }
                        if (rs.getObject("option_value_id") != null) {
                            sku.options!!.put(rs.getString("group_name"), rs.getString("value_name"))
                        }
                    }
                    ArrayList(result.values)
                },
            )!!
            StoreCatalog.Product(
                product.productId,
                product.name,
                product.description,
                product.imageUrl,
                product.status,
                product.categoryId,
                product.categoryName,
                product.viewCount,
                product.createdAt,
                product.updatedAt,
                skus,
            )
        }!!

    private data class CategoryRow(
        val id: Long?,
        val name: String?,
        val description: String?,
        val parentId: Long?,
    )

    override fun categories(): List<StoreCatalog.Category> =
        transactions.execute {
            val rows: List<CategoryRow> = jdbc.query(
                CATEGORIES,
                emptyMap<String, Any>(),
                RowMapper { rs, _ ->
                    CategoryRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getObject("parent_id", Long::class.javaObjectType),
                    )
                },
            )
            rows.stream().filter { row -> row.parentId == null }.map { row -> category(row, rows) }.toList()
        }!!

    private fun category(row: CategoryRow, rows: List<CategoryRow>): StoreCatalog.Category =
        StoreCatalog.Category(
            row.id,
            row.name,
            row.description,
            row.parentId,
            rows.stream()
                .filter { child -> row.id == child.parentId }
                .map { child -> category(child, rows) }
                .toList(),
        )

    override fun optionGroups(): List<StoreCatalog.OptionGroup> =
        transactions.execute { readOptions(OPTIONS, emptyMap<String, Any>()) }!!

    override fun optionGroup(groupId: Long): StoreCatalog.OptionGroup =
        transactions.execute {
            val groups = readOptions(OPTIONS + " WHERE g.id=:id", mapOf("id" to groupId))
            if (groups.isEmpty()) {
                throw IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: $groupId")
            }
            groups.first()
        }!!

    private fun readOptions(sql: String, parameters: Map<String, *>): List<StoreCatalog.OptionGroup> =
        jdbc.query(
            sql,
            parameters,
            ResultSetExtractor { rs ->
                val groups = LinkedHashMap<Long, StoreCatalog.OptionGroup>()
                while (rs.next()) {
                    val id = rs.getLong("group_id")
                    var group = groups[id]
                    if (group == null) {
                        group = StoreCatalog.OptionGroup(id, rs.getString("group_name"), ArrayList())
                        groups[id] = group
                    }
                    if (rs.getObject("value_id") != null) {
                        group.values!!.add(
                            StoreCatalog.OptionValue(rs.getLong("value_id"), rs.getString("value_name"), id, group.name),
                        )
                    }
                }
                ArrayList(groups.values)
            },
        )!!

    override fun close() {
        terms.close()
    }

    private companion object {
        private val PRODUCT = """
            SELECT p.id AS product_id,p.name,p.description,p.image_url,p.status,p.category_id,
                   c.name AS category_name,p.view_count,p.created_at,p.updated_at
            FROM pawbridge_store.products p LEFT JOIN pawbridge_store.categories c ON c.id=p.category_id
            WHERE p.id=:id
        """.trimIndent() + "\n"

        private val SKUS = """
            SELECT s.id AS sku_id,s.sku_code,s.price,s.stock_quantity,
                   v.id AS option_value_id,g.name AS group_name,v.name AS value_name
            FROM pawbridge_store.product_skus s
            LEFT JOIN pawbridge_store.sku_values sv ON sv.product_sku_id=s.id
            LEFT JOIN pawbridge_store.option_values v ON v.id=sv.option_value_id
            LEFT JOIN pawbridge_store.option_groups g ON g.id=v.option_group_id
            WHERE s.product_id=:id
        """.trimIndent() + "\n"

        private val CATEGORIES = """
            SELECT id,name,description,parent_id FROM pawbridge_store.categories
        """.trimIndent() + "\n"

        private val OPTIONS = """
            SELECT g.id AS group_id,g.name AS group_name,v.id AS value_id,v.name AS value_name
            FROM pawbridge_store.option_groups g
            LEFT JOIN pawbridge_store.option_values v ON v.option_group_id=g.id
        """.trimIndent() + "\n"

        // Used for keyword searches: the search documents must match the current source rows.
        private val READINESS = """
            NOT EXISTS(SELECT 1 FROM pawbridge_store.product_search_source s LEFT JOIN pawbridge_store.product_search_documents d ON d.product_id=s.product_id
                WHERE s.status='ACTIVE' AND (d.product_id IS NULL OR d.sku_id<>s.sku_id OR d.source_name<>s.name
                    OR d.source_option<>s.option_name OR d.analyzer_version<>:version))
        """.trimIndent() + "\n"
    }
}
