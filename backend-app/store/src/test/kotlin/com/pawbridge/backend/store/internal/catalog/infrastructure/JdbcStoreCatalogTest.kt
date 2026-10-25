package com.pawbridge.backend.store.internal.catalog.infrastructure

import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class JdbcStoreCatalogTest {
    private val jdbc = mockk<NamedParameterJdbcOperations>()
    private val tx = mockk<TransactionOperations>()
    private val catalog = JdbcStoreCatalog(jdbc, tx)
    private var active = false
    private val sqls = ArrayList<String>()
    private val parameters = ArrayList<Map<String, *>>()
    private val time: LocalDateTime = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000)

    @AfterEach
    fun close() {
        catalog.close()
    }

    @ParameterizedTest
    @MethodSource("invalid")
    fun `잘못된 검색 요청일 때__조회하면__트랜잭션이나 SQL 전에 거부한다`(request: StoreCatalog.Search) {
        // given

        // when
        assertThrows(IllegalArgumentException::class.java) { catalog.search(request) }

        // then
        confirmVerified(jdbc, tx)
    }

    @Test
    fun `분석기가 구두점만 남길 때__검색하면__데이터베이스 없이 빈 결과를 돌려준다`() {
        // given
        val request = search("!!!", null, null, null, false, null, null, 3, 20)

        // when
        val result = catalog.search(request)

        // then
        assertEquals(StoreCatalog.SearchResult(listOf(), 0L, 3, 0, false), result)
        confirmVerified(jdbc, tx)
    }

    @Test
    fun `모든 필터와 검색어가 있을 때__검색하면__바인딩된 CTE와 원래의 항목 매핑을 유지한다`() {
        // given
        val request = search("alpha alpha beta", 0L, 9007199254740993L, 8L, true, "minPrice", "aSc", 0, 20)

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), listOf(searchRow()))
        }

        // when
        val result = catalog.search(request)

        // then
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
    fun `키워드가 공백이고 나머지가 null일 때__검색하면__필터 없는 재고와 기본 정렬을 유지한다`() {
        // given
        val request = search(" ", null, null, null, null, null, null, null, null)

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), listOf(map("ready", true, "total_count", 0L)))
        }

        // when
        val result = catalog.search(request)

        // then
        assertEquals(StoreCatalog.SearchResult(listOf(), 0L, 0, 0, false), result)
        assertTrue(sqls.first().contains("WITH readiness AS (SELECT true AS ready)"))
        assertTrue(sqls.first().endsWith("ORDER BY sku_id DESC NULLS LAST,sku_id ASC"))
        assertFalse(sqls.first().contains("s.total_stock>0"))
        assertFalse(sqls.first().contains("NOT EXISTS"))
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun `허용된 정렬과 마지막 offset일 때__검색하면__정렬과 빈 페이지 총계를 유지한다`(
        sort: String,
        column: String,
    ) {
        // given
        val request = search(null, null, null, null, false, sort, "DESC", 1000, 100)

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), listOf(map("ready", true, "total_count", 3L)))
        }

        // when
        val result = catalog.search(request)

        // then
        assertEquals(StoreCatalog.SearchResult(listOf(), 3L, 1000, 1, false), result)
        assertTrue(sqls.first().endsWith("ORDER BY $column DESC NULLS LAST,sku_id ASC"))
        assertEquals(100000L, parameters.first()["offset"])
    }

    @Test
    fun `프로젝션이 준비되지 않았을 때__검색하면__빈 성공 대신 실패한다`() {
        // given
        val request = search("사료", null, null, null, false, null, null, 0, 20)

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), listOf(map("ready", false)))
        }

        // when
        assertThrows(CatalogSearchUnavailableException::class.java) { catalog.search(request) }

        // then
        assertFalse(active)
    }

    @Test
    fun `재고가 정수 범위를 넘을 때__매핑하면__잘라내지 않고 매핑 실패를 보존한다`() {
        // given
        val row = searchRow()
        row["total_stock"] = 2147483648L
        val request = search(null, null, null, null, false, null, null, 0, 20)

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), listOf(row))
        }

        // when
        assertThrows(ArithmeticException::class.java) { catalog.search(request) }

        // then
        assertEquals(1, sqls.size)
    }

    @Test
    fun `SQL이 실패할 때__검색하면__검색 불가로 변환한다`() {
        // given
        val failure = DataAccessResourceFailureException("synthetic private DB detail")
        val request = search(null, null, null, null, false, null, null, 0, 20)

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } throws failure

        // when
        val thrown = assertThrows(CatalogSearchUnavailableException::class.java) { catalog.search(request) }

        // then
        assertSame(failure, thrown.cause)
        assertFalse(active)
    }

    @ParameterizedTest
    @MethodSource("txFailures")
    fun `트랜잭션을 쓸 수 없을 때__검색하면__검색 실패를 돌려준다`(failure: RuntimeException) {
        // given
        val request = search(null, null, null, null, false, null, null, 0, 20)

        every { tx.execute(any<TransactionCallback<Any>>()) } throws failure

        // when
        val thrown = assertThrows(CatalogSearchUnavailableException::class.java) { catalog.search(request) }

        // then
        assertSame(failure, thrown.cause)
        confirmVerified(jdbc)
    }

    @Test
    fun `예상 밖 트랜잭션 실패일 때__검색하면__503으로 가리지 않는다`() {
        // given
        val failure = TransactionSystemException("unexpected")
        val request = search(null, null, null, null, false, null, null, 0, 20)

        every { tx.execute(any<TransactionCallback<Any>>()) } throws failure

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { catalog.search(request) }

        // then
        assertSame(failure, thrown)
    }

    @Test
    fun `삭제된 상품이 null과 SKU를 갖고 있을 때__읽으면__전체 상세와 옵션을 한 트랜잭션에서 유지한다`() {
        // given
        val productRows = listOf(
            map(
                "product_id", 9L, "name", "상품", "description", null, "image_url", null, "status", "DELETED",
                "category_id", null, "category_name", null, "view_count", 2L, "created_at", time, "updated_at", null,
            ),
        )
        val skuRows = listOf(
            map(
                "sku_id", 31L, "sku_code", "A", "price", 9007199254740993L, "stock_quantity", 0,
                "option_value_id", 3L, "group_name", "색상", "value_name", "파랑",
            ),
            map("sku_id", 31L, "option_value_id", 4L, "group_name", "크기", "value_name", "대"),
            map("sku_id", 32L, "sku_code", "B", "price", 0L, "stock_quantity", 4, "option_value_id", null),
        )

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<RowMapper<Any>>()) } answers {
            mapped(firstArg(), secondArg(), thirdArg(), productRows)
        }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), skuRows)
        }

        // when
        val product = catalog.product(9L)

        // then
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
        verify(exactly = 1) { tx.execute(any<TransactionCallback<Any>>()) }
        assertFalse(active)
    }

    @Test
    fun `없는 상품일 때__읽으면__SKU 조회 없이 잘못된 입력을 보존한다`() {
        // given
        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<RowMapper<Any>>()) } answers {
            mapped(firstArg(), secondArg(), thirdArg(), listOf())
        }

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { catalog.product(9L) }

        // then
        assertEquals("Product not found: 9", thrown.message)
        assertEquals(1, sqls.size)
    }

    @Test
    fun `SKU 없이 카테고리만 있는 상품일 때__읽으면__카테고리와 빈 목록을 유지한다`() {
        // given
        val productRows = listOf(
            map("product_id", 9L, "name", "상품", "status", "SOLD_OUT", "category_id", 8L, "category_name", "사료", "view_count", 0L),
        )

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<RowMapper<Any>>()) } answers {
            mapped(firstArg(), secondArg(), thirdArg(), productRows)
        }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), listOf())
        }

        // when
        val product = catalog.product(9L)

        // then
        assertEquals(
            StoreCatalog.Product(9L, "상품", null, null, "SOLD_OUT", 8L, "사료", 0L, null, null, listOf()),
            product,
        )
    }

    @Test
    fun `루트와 자식과 손자가 있을 때__카테고리를 읽으면__재귀 자식을 가진 루트를 돌려준다`() {
        // given
        val categoryRows = listOf(
            map("id", 2L, "name", "자식", "description", null, "parent_id", 1L),
            map("id", 1L, "name", "루트", "description", "설명", "parent_id", null),
            map("id", 3L, "name", "손자", "description", null, "parent_id", 2L),
        )

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<RowMapper<Any>>()) } answers {
            mapped(firstArg(), secondArg(), thirdArg(), categoryRows)
        }

        // when
        val categories = catalog.categories()

        // then
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
            categories,
        )
    }

    @Test
    fun `값이 있는 그룹과 없는 그룹이 있을 때__읽으면__그룹과 값의 그룹 필드를 유지한다`() {
        // given
        val groupRows = listOf(
            map("group_id", 1L, "group_name", "색상", "value_id", 3L, "value_name", "파랑"),
            map("group_id", 1L, "group_name", "색상", "value_id", 4L, "value_name", "초록"),
            map("group_id", 2L, "group_name", "크기", "value_id", null),
        )

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), groupRows)
        }

        // when
        val groups = catalog.optionGroups()

        // then
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
            groups,
        )
    }

    @Test
    fun `그룹이 하나일 때__상세를 읽으면__id를 바인딩하고 빈 값 목록을 유지한다`() {
        // given
        val groupRows = listOf(map("group_id", 2L, "group_name", "크기", "value_id", null))

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), groupRows)
        }

        // when
        val group = catalog.optionGroup(2)

        // then
        assertEquals(StoreCatalog.OptionGroup(2L, "크기", mutableListOf()), group)
        assertTrue(sqls.first().endsWith(" WHERE g.id=:id"))
        assertEquals(mapOf("id" to 2L), parameters.first())
    }

    @Test
    fun `없는 옵션 그룹일 때__상세를 읽으면__원래의 잘못된 입력을 보존한다`() {
        // given
        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<ResultSetExtractor<Any>>()) } answers {
            extract(firstArg(), secondArg(), thirdArg(), listOf())
        }

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { catalog.optionGroup(77) }

        // then
        assertEquals("옵션 그룹을 찾을 수 없습니다: 77", thrown.message)
    }

    @Test
    fun `상세 조회 데이터베이스가 실패할 때__읽으면__검색 불가로 바꾸지 않는다`() {
        // given
        val failure = DataAccessResourceFailureException("synthetic")

        every { tx.execute(any<TransactionCallback<Any>>()) } answers { inTransaction(firstArg()) }
        every { jdbc.query(any<String>(), any<Map<String, Any>>(), any<RowMapper<Any>>()) } throws failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { catalog.product(9) }

        // then
        assertSame(failure, thrown)
    }

    private fun searchRow(): MutableMap<String, Any?> {
        return map(
            "ready", true, "total_count", 21L, "product_id", 7L, "sku_id", 17L, "name", "상품",
            "option_name", "Color: Blue", "image_url", null, "price", 9007199254740993L, "total_stock", 0L,
            "status", "ACTIVE", "created_at", time, "updated_at", null,
        )
    }

    /** Runs the callback the way a real transaction would: active only while the callback is executing. */
    private fun inTransaction(callback: TransactionCallback<*>): Any? {
        active = true
        try {
            return callback.doInTransaction(SimpleTransactionStatus())
        } finally {
            active = false
        }
    }

    private fun extract(
        sql: String,
        params: Map<String, *>,
        extractor: ResultSetExtractor<*>,
        values: List<Map<String, Any?>>,
    ): Any? {
        assertTrue(active)
        sqls.add(sql)
        parameters.add(params)
        return extractor.extractData(rows(*values.toTypedArray()))
    }

    private fun mapped(
        sql: String,
        params: Map<String, *>,
        mapper: RowMapper<*>,
        values: List<Map<String, Any?>>,
    ): List<Any?> {
        assertTrue(active)
        sqls.add(sql)
        parameters.add(params)
        val rs = rows(*values.toTypedArray())
        val result = ArrayList<Any?>()
        var i = 0
        while (rs.next()) {
            result.add(mapper.mapRow(rs, i++))
        }
        return result
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

        /**
         * A hand-written in-memory ResultSet over the given rows (a data fake, not a mock): `next` advances the cursor,
         * `get*` with a column name reads the current row, and every other method returns the type's zero value.
         */
        private fun rows(vararg values: Map<String, Any?>): ResultSet {
            val cursor = AtomicInteger(-1)
            return Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf(ResultSet::class.java),
            ) { proxy, method, args ->
                val name = method.name
                if (method.declaringClass == Any::class.java) {
                    return@newProxyInstance when (name) {
                        "equals" -> proxy === args[0]
                        "hashCode" -> System.identityHashCode(proxy)
                        else -> "InMemoryResultSet"
                    }
                }
                if (name == "next") {
                    return@newProxyInstance cursor.incrementAndGet() < values.size
                }
                if (name.startsWith("get") && args != null && args[0] is String) {
                    val value = values[cursor.get()][args[0] as String]
                    return@newProxyInstance when (name) {
                        "getLong" -> if (value == null) 0L else (value as Number).toLong()
                        "getInt" -> if (value == null) 0 else (value as Number).toInt()
                        "getBoolean" -> java.lang.Boolean.TRUE == value
                        else -> value
                    }
                }
                when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            } as ResultSet
        }
    }
}
