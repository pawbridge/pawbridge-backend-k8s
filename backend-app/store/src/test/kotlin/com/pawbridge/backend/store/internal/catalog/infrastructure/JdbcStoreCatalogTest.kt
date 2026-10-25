package com.pawbridge.backend.store.internal.catalog.infrastructure

import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.stubbing.Answer
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class JdbcStoreCatalogTest {
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    private val tx: TransactionOperations = mock(TransactionOperations::class.java)
    private val catalog = JdbcStoreCatalog(jdbc, tx)
    private var active = false
    private val sqls = ArrayList<String>()
    private val parameters = ArrayList<Map<String, *>>()
    private val time: LocalDateTime = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000)

    @BeforeEach
    fun transaction() {
        `when`(tx.execute(any<TransactionCallback<Any>>())).thenAnswer { call ->
            active = true
            try {
                call.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
            } finally {
                active = false
            }
        }
    }

    @AfterEach
    fun close() {
        catalog.close()
    }

    @ParameterizedTest
    @MethodSource("invalid")
    fun given__invalid_search__when__reading__then__reject_before_transaction_or_sql(request: StoreCatalog.Search) {
        assertThrows(IllegalArgumentException::class.java) { catalog.search(request) }

        verifyNoInteractions(jdbc, tx)
    }

    @Test
    fun given__analyzer_only_punctuation__when__searching__then__empty_without_database() {
        assertEquals(
            StoreCatalog.SearchResult(listOf(), 0L, 3, 0, false),
            catalog.search(search("!!!", null, null, null, false, null, null, 3, 20)),
        )

        verifyNoInteractions(jdbc, tx)
    }

    @Test
    fun given__all_filters_and_terms__when__searching__then__retain_bound_cte_and_original_item_mapping() {
        extractRows(searchRow())

        val result = catalog.search(search("alpha alpha beta", 0L, 9007199254740993L, 8L, true, "minPrice", "aSc", 0, 20))

        assertEquals(
            StoreCatalog.SearchResult(
                listOf(
                    StoreCatalog.Item(
                        7L, 17L, "상품", "Color: Blue", "상품", null, 9007199254740993L, 0, "ACTIVE", time, null,
                    ),
                ),
                21L,
                0,
                2,
                true,
            ),
            result,
        )
        val sql = sqls.first()
        assertTrue(sql.contains("s.status='ACTIVE'"))
        assertTrue(sql.contains("s.category_id=:category"))
        assertTrue(sql.contains("s.price>=:min"))
        assertTrue(sql.contains("s.price<=:max"))
        assertTrue(sql.contains("s.total_stock>0"))
        assertTrue(sql.contains("d.tokens @@ to_tsquery('simple',:query)"))
        assertTrue(sql.contains("d.source_option<>s.option_name OR d.analyzer_version<>:version"))
        assertTrue(sql.contains("LEFT JOIN sliced ON readiness.ready"))
        assertTrue(sql.endsWith("ORDER BY price ASC NULLS LAST,sku_id ASC"))
        assertFalse(sql.contains("alpha"))
        assertEquals(
            mapOf<String, Any>(
                "offset" to 0L,
                "size" to 20,
                "query" to "'alpha' | 'beta'",
                "version" to "nori-9.12.3-v1",
                "category" to 8L,
                "min" to 0L,
                "max" to 9007199254740993L,
            ),
            parameters.first(),
        )
        assertFalse(active)
    }

    @Test
    fun given__blank_keyword_and_null_defaults__when__searching__then__retain_unfiltered_stock_and_default_sort() {
        extractRows(map("ready", true, "total_count", 0L))

        assertEquals(
            StoreCatalog.SearchResult(listOf(), 0L, 0, 0, false),
            catalog.search(search(" ", null, null, null, null, null, null, null, null)),
        )

        assertTrue(sqls.first().contains("WITH readiness AS (SELECT true AS ready)"))
        assertTrue(sqls.first().endsWith("ORDER BY sku_id DESC NULLS LAST,sku_id ASC"))
        assertFalse(sqls.first().contains("s.total_stock>0"))
        assertFalse(sqls.first().contains("NOT EXISTS"))
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun given__allowed_sort_and_last_offset__when__searching__then__retain_sort_and_empty_page_total(
        sort: String,
        column: String,
    ) {
        extractRows(map("ready", true, "total_count", 3L))

        assertEquals(
            StoreCatalog.SearchResult(listOf(), 3L, 1000, 1, false),
            catalog.search(search(null, null, null, null, false, sort, "DESC", 1000, 100)),
        )

        assertTrue(sqls.first().endsWith("ORDER BY $column DESC NULLS LAST,sku_id ASC"))
        assertEquals(100000L, parameters.first()["offset"])
    }

    @Test
    fun given__unready_projection__when__searching__then__fail_instead_of_returning_empty_success() {
        extractRows(map("ready", false))

        assertThrows(CatalogSearchUnavailableException::class.java) {
            catalog.search(search("사료", null, null, null, false, null, null, 0, 20))
        }

        assertFalse(active)
    }

    @Test
    fun given__overflowing_stock__when__mapping__then__preserve_mapping_failure_instead_of_truncating() {
        val row = searchRow()
        row["total_stock"] = 2147483648L
        extractRows(row)

        assertThrows(ArithmeticException::class.java) {
            catalog.search(search(null, null, null, null, false, null, null, 0, 20))
        }
    }

    @Test
    fun given__sql_failure__when__searching__then__translate_to_search_unavailable() {
        val failure = DataAccessResourceFailureException("synthetic private DB detail")
        doThrow(failure).`when`(jdbc).query(anyString(), anyMap<String, Any>(), any<ResultSetExtractor<Any>>())

        assertSame(
            failure,
            assertThrows(CatalogSearchUnavailableException::class.java) {
                catalog.search(search(null, null, null, null, false, null, null, 0, 20))
            }.cause,
        )

        assertFalse(active)
    }

    @ParameterizedTest
    @MethodSource("txFailures")
    fun given__transaction_unavailable__when__searching__then__return_search_failure(failure: RuntimeException) {
        doThrow(failure).`when`(tx).execute(any<TransactionCallback<Any>>())

        assertSame(
            failure,
            assertThrows(CatalogSearchUnavailableException::class.java) {
                catalog.search(search(null, null, null, null, false, null, null, 0, 20))
            }.cause,
        )

        verifyNoInteractions(jdbc)
    }

    @Test
    fun given__unexpected_transaction_failure__when__searching__then__do_not_mask_as_503() {
        val failure = TransactionSystemException("unexpected")
        doThrow(failure).`when`(tx).execute(any<TransactionCallback<Any>>())

        assertSame(
            failure,
            assertThrows(TransactionSystemException::class.java) {
                catalog.search(search(null, null, null, null, false, null, null, 0, 20))
            },
        )
    }

    @Test
    fun given__deleted_product_nulls_and_skus__when__reading__then__retain_full_detail_and_options_in_one_transaction() {
        mapRows(
            map(
                "product_id", 9L, "name", "상품", "description", null, "image_url", null, "status", "DELETED",
                "category_id", null, "category_name", null, "view_count", 2L, "created_at", time, "updated_at", null,
            ),
        )
        extractRows(
            map(
                "sku_id", 31L, "sku_code", "A", "price", 9007199254740993L, "stock_quantity", 0,
                "option_value_id", 3L, "group_name", "색상", "value_name", "파랑",
            ),
            map("sku_id", 31L, "option_value_id", 4L, "group_name", "크기", "value_name", "대"),
            map("sku_id", 32L, "sku_code", "B", "price", 0L, "stock_quantity", 4, "option_value_id", null),
        )

        val product = catalog.product(9L)

        assertEquals(
            StoreCatalog.Product(
                9L, "상품", null, null, "DELETED", null, null, 2L, time, null,
                listOf(
                    StoreCatalog.Sku(31L, "A", 9007199254740993L, 0, mutableMapOf("색상" to "파랑", "크기" to "대")),
                    StoreCatalog.Sku(32L, "B", 0L, 4, mutableMapOf()),
                ),
            ),
            product,
        )
        assertTrue(sqls.first().contains("WHERE p.id=:id"))
        assertFalse(sqls.first().contains("p.status="))
        assertEquals(listOf(mapOf("id" to 9L), mapOf("id" to 9L)), parameters)
        verify(tx, times(1)).execute(any<TransactionCallback<Any>>())
        assertFalse(active)
    }

    @Test
    fun given__missing_product__when__reading__then__preserve_bad_input_without_sku_query() {
        mapRows()

        assertEquals(
            "Product not found: 9",
            assertThrows(IllegalArgumentException::class.java) { catalog.product(9L) }.message,
        )

        assertEquals(1, sqls.size)
    }

    @Test
    fun given__product_without_skus_and_category__when__reading__then__retain_category_and_empty_list() {
        mapRows(map("product_id", 9L, "name", "상품", "status", "SOLD_OUT", "category_id", 8L, "category_name", "사료", "view_count", 0L))
        extractRows()

        assertEquals(
            StoreCatalog.Product(9L, "상품", null, null, "SOLD_OUT", 8L, "사료", 0L, null, null, listOf()),
            catalog.product(9L),
        )
    }

    @Test
    fun given__root_child_and_grandchild__when__reading_categories__then__return_roots_with_recursive_children() {
        mapRows(
            map("id", 2L, "name", "자식", "description", null, "parent_id", 1L),
            map("id", 1L, "name", "루트", "description", "설명", "parent_id", null),
            map("id", 3L, "name", "손자", "description", null, "parent_id", 2L),
        )

        assertEquals(
            listOf(
                StoreCatalog.Category(
                    1L, "루트", "설명", null,
                    listOf(
                        StoreCatalog.Category(
                            2L, "자식", null, 1L,
                            listOf(StoreCatalog.Category(3L, "손자", null, 2L, listOf())),
                        ),
                    ),
                ),
            ),
            catalog.categories(),
        )
    }

    @Test
    fun given__groups_with_and_without_values__when__reading__then__retain_groups_and_value_group_fields() {
        extractRows(
            map("group_id", 1L, "group_name", "색상", "value_id", 3L, "value_name", "파랑"),
            map("group_id", 1L, "group_name", "색상", "value_id", 4L, "value_name", "초록"),
            map("group_id", 2L, "group_name", "크기", "value_id", null),
        )

        assertEquals(
            listOf(
                StoreCatalog.OptionGroup(
                    1L,
                    "색상",
                    mutableListOf(
                        StoreCatalog.OptionValue(3L, "파랑", 1L, "색상"),
                        StoreCatalog.OptionValue(4L, "초록", 1L, "색상"),
                    ),
                ),
                StoreCatalog.OptionGroup(2L, "크기", mutableListOf()),
            ),
            catalog.optionGroups(),
        )
    }

    @Test
    fun given__one_group__when__reading_detail__then__bind_id_and_keep_empty_values() {
        extractRows(map("group_id", 2L, "group_name", "크기", "value_id", null))

        assertEquals(StoreCatalog.OptionGroup(2L, "크기", mutableListOf()), catalog.optionGroup(2))

        assertTrue(sqls.first().endsWith(" WHERE g.id=:id"))
        assertEquals(mapOf("id" to 2L), parameters.first())
    }

    @Test
    fun given__missing_option_group__when__reading_detail__then__preserve_original_bad_input() {
        extractRows()

        assertEquals(
            "옵션 그룹을 찾을 수 없습니다: 77",
            assertThrows(IllegalArgumentException::class.java) { catalog.optionGroup(77) }.message,
        )
    }

    @Test
    fun given__detail_database_failure__when__reading__then__do_not_convert_to_search_unavailable() {
        val failure = DataAccessResourceFailureException("synthetic")
        doThrow(failure).`when`(jdbc).query(anyString(), anyMap<String, Any>(), any<RowMapper<Any>>())

        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { catalog.product(9) })
    }

    private fun searchRow(): MutableMap<String, Any?> {
        return map(
            "ready", true, "total_count", 21L, "product_id", 7L, "sku_id", 17L, "name", "상품",
            "option_name", "Color: Blue", "image_url", null, "price", 9007199254740993L, "total_stock", 0L,
            "status", "ACTIVE", "created_at", time, "updated_at", null,
        )
    }

    private fun extractRows(vararg values: Map<String, Any?>) {
        `when`(jdbc.query(anyString(), anyMap<String, Any>(), any<ResultSetExtractor<Any>>())).thenAnswer { call ->
            assertTrue(active)
            sqls.add(call.getArgument(0))
            parameters.add(call.getArgument(1))
            call.getArgument<ResultSetExtractor<*>>(2).extractData(rows(*values))
        }
    }

    private fun mapRows(vararg values: Map<String, Any?>) {
        `when`(jdbc.query(anyString(), anyMap<String, Any>(), any<RowMapper<Any>>())).thenAnswer { call ->
            assertTrue(active)
            sqls.add(call.getArgument(0))
            parameters.add(call.getArgument(1))
            val rs = rows(*values)
            val mapper = call.getArgument<RowMapper<*>>(2)
            val result = ArrayList<Any?>()
            var i = 0
            while (rs.next()) {
                result.add(mapper.mapRow(rs, i++))
            }
            result
        }
    }

    companion object {
        @JvmStatic
        fun invalid(): Stream<Arguments> {
            return Stream.of(
                search(null, null, null, null, null, null, null, -1, 20),
                search(null, null, null, null, null, null, null, 0, 0),
                search(null, null, null, null, null, null, null, 0, 101),
                search(null, null, null, null, null, null, null, 5001, 20),
                search(null, -1L, null, null, null, null, null, 0, 20),
                search(null, null, -1L, null, null, null, null, 0, 20),
                search(null, 2L, 1L, null, null, null, null, 0, 20),
                search(null, null, null, null, null, "price;DROP TABLE products", null, 0, 20),
                search(null, null, null, null, null, null, "desc;--", 0, 20),
                search("x".repeat(201), null, null, null, null, null, null, 0, 20),
                search("a\u0000b", null, null, null, null, null, null, 0, 20),
            ).map { Arguments.of(it) }
        }

        @JvmStatic
        fun sorts(): Stream<Arguments> = Stream.of(
            Arguments.of("skuId", "sku_id"),
            Arguments.of("price", "price"),
            Arguments.of("createdAt", "created_at"),
        )

        @JvmStatic
        fun txFailures(): Stream<Arguments> = Stream.of(
            Arguments.of(CannotCreateTransactionException("offline")),
            Arguments.of(TransactionTimedOutException("timeout")),
        )

        private fun search(
            k: String?,
            min: Long?,
            max: Long?,
            category: Long?,
            stock: Boolean?,
            sort: String?,
            direction: String?,
            page: Int?,
            size: Int?,
        ): StoreCatalog.Search {
            return StoreCatalog.Search(k, min, max, category, stock, sort, direction, page, size)
        }

        private fun map(vararg cells: Any?): MutableMap<String, Any?> {
            val result = HashMap<String, Any?>()
            var i = 0
            while (i < cells.size) {
                result[cells[i] as String] = cells[i + 1]
                i += 2
            }
            return result
        }

        private fun rows(vararg values: Map<String, Any?>): ResultSet {
            val i = AtomicInteger(-1)
            return mock(
                ResultSet::class.java,
                Answer<Any?> { call ->
                    val method = call.method.name
                    if (method == "next") {
                        return@Answer i.incrementAndGet() < values.size
                    }
                    if (method.startsWith("get") && call.getArgument<Any?>(0) is String) {
                        val column = call.getArgument<String>(0)
                        val value = values[i.get()][column]
                        return@Answer when (method) {
                            "getLong" -> if (value == null) 0L else (value as Number).toLong()
                            "getInt" -> if (value == null) 0 else (value as Number).toInt()
                            "getBoolean" -> java.lang.Boolean.TRUE == value
                            else -> value
                        }
                    }
                    RETURNS_DEFAULTS.answer(call)
                },
            )
        }
    }
}
