package com.pawbridge.backend.store.internal.memberread.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException
import com.pawbridge.backend.store.api.StoreMemberReads
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.Queue
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class JdbcStoreMemberReadsTest {
    private val jdbc: NamedParameterJdbcOperations = mockk<NamedParameterJdbcOperations>()
    private val tx: TransactionOperations = mockk<TransactionOperations>()
    private val reads = JdbcStoreMemberReads(jdbc, tx)
    private val mapped: Queue<ResultSet> = java.util.ArrayDeque()
    private val extracted: Queue<ResultSet> = java.util.ArrayDeque()
    private val sql = ArrayList<String>()
    private val params = ArrayList<Map<String, *>>()
    private var active = false
    private val time: LocalDateTime = LocalDateTime.of(2026, 10, 20, 3, 4, 5, 123456000)

    @Test
    fun `위시리스트 페이지와 옵션 행이 있을 때__읽으면__회원 범위와 부모 순서와 모든 상태와 원본 필드를 보존한다`() {
        // given
        extracted.add(
            rows(
                wish(11L, 21L, "HIDDEN", null, null, null, null, null),
                wish(12L, 22L, "DELETED", 8L, "사료", 3L, "색상", "파랑"),
                wish(12L, 22L, "DELETED", 8L, "사료", 4L, "크기", "대"),
            ),
        )

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Long::class.javaObjectType) } answers {
            capture(firstArg<String>(), secondArg<Map<String, *>>())
            3L
        }

        // when
        val result = reads.wishlists(7, request(0, 2))

        // then
        assertEquals(
            listOf(
                StoreMemberReads.Wishlist(
                    11L, 21L, "SKU21", 101L, "상품21", null, null, "HIDDEN", 9007199254740993L,
                    mutableMapOf(), null, null, time,
                ),
                StoreMemberReads.Wishlist(
                    12L, 22L, "SKU22", 102L, "상품22", null, null, "DELETED", 9007199254740993L,
                    mutableMapOf<String?, String?>("색상" to "파랑", "크기" to "대"), 8L, "사료", time,
                ),
            ),
            result.content,
        )
        assertEquals(3L, result.totalElements)
        assertEquals(0, result.number)
        assertEquals(2, result.size)
        assertTrue(sql.first().contains("w.user_id=:member ORDER BY w.created_at DESC LIMIT :size OFFSET :offset"))
        assertTrue(sql.first().startsWith("WITH selected AS (SELECT w.id"))
        assertTrue(sql.first().contains("w.user_id=:member AND w.id IN (SELECT id FROM selected)"))
        assertTrue(
            sql.first().indexOf("LIMIT :size OFFSET :offset") < sql.first().indexOf("LEFT JOIN pawbridge_store.sku_values"),
        )
        assertFalse(sql.first().contains("p.status="))
        assertEquals(2, sql.size)
        assertMember(7)
        assertFalse(active)
        verify(exactly = 1) { tx.execute(any<TransactionCallback<Any>>()) }
    }

    @Test
    fun `첫 페이지에 위시리스트가 없을 때__읽으면__상세나 개수 조회 없이 빈 페이지를 돌려준다`() {
        // given
        extracted.add(rows())

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
        }

        // when
        val result = reads.wishlists(19, request(0, 20))

        // then
        assertEquals(
            StoreMemberReads.Page<StoreMemberReads.Wishlist>(listOf(), 0, 20, 0L, request(0, 20).sort()),
            result,
        )
        assertEquals(1, sql.size)
        assertMember(19)
    }

    @Test
    fun `위시리스트 뒷 페이지가 비어 있을 때__읽으면__전체 개수와 정렬 없는 요청을 유지한다`() {
        // given
        extracted.add(rows())
        val request = StoreMemberReads.Request(3, 2, listOf())

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Long::class.javaObjectType) } answers {
            capture(firstArg<String>(), secondArg<Map<String, *>>())
            2L
        }

        // when
        val result = reads.wishlists(7, request)

        // then
        assertEquals(2L, result.totalElements)
        assertEquals(listOf<StoreMemberReads.Order>(), result.sort)
        assertFalse(sql.first().contains("ORDER BY"))
        assertEquals(6L, params.first()["offset"])
        assertEquals(2, sql.size)
    }

    @Test
    fun `위시리스트 뒷 페이지가 일부만 찼을 때__읽으면__개수 조회 없이 총계를 계산한다`() {
        // given
        extracted.add(rows(wish(11L, 21L, "ACTIVE", null, null, null, null, null)))

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
        }

        // when
        val result = reads.wishlists(7, request(2, 2))

        // then
        assertEquals(5L, result.totalElements)
        assertEquals(1, sql.size)
    }

    @Test
    fun `주문 헤더 두 개와 순서가 뒤바뀐 항목 행이 있을 때__읽으면__주문 시점 스냅샷과 페이지 경계를 보존한다`() {
        // given
        mapped.add(rows(purchase(9, "CANCELLED"), purchase(10, "FAILED")))
        extracted.add(
            rows(
                map(
                    "order_id", 10L, "item_id", 31L, "product_sku_id", 71L, "product_name", "구매시상품B",
                    "sku_code", "OLD-B", "price", 4L, "quantity", 5,
                ),
                map(
                    "order_id", 9L, "item_id", 32L, "product_sku_id", 72L, "product_name", "구매시상품A",
                    "sku_code", "OLD-A", "price", 9007199254740993L, "quantity", 1,
                ),
            ),
        )

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<Any>>()) } answers {
            mapRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<RowMapper<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
        }
        every { jdbc.queryForObject(any<String>(), any<Map<String, *>>(), Long::class.javaObjectType) } answers {
            capture(firstArg<String>(), secondArg<Map<String, *>>())
            3L
        }

        // when
        val result = reads.orders(7, request(0, 2))

        // then
        assertEquals(
            listOf(
                StoreMemberReads.Purchase(
                    9L, "uuid9", 9007199254740993L, "CANCELLED", "주소", "READY",
                    mutableListOf(StoreMemberReads.OrderItem(32L, 72L, "구매시상품A", "OLD-A", 9007199254740993L, 1)),
                    time,
                ),
                StoreMemberReads.Purchase(
                    10L, "uuid10", 9007199254740993L, "FAILED", "주소", "READY",
                    mutableListOf(StoreMemberReads.OrderItem(31L, 71L, "구매시상품B", "OLD-B", 4L, 5)),
                    time,
                ),
            ),
            result.content,
        )
        assertEquals(3L, result.totalElements)
        assertTrue(sql.first().contains("LIMIT :size OFFSET :offset"))
        assertTrue(sql[1].contains("o.user_id=:member AND o.id IN (:ids)"))
        assertFalse(sql[1].contains("product_skus"))
        assertFalse(sql.first().contains("o.status="))
        assertMember(7)
    }

    @Test
    fun `항목 없는 저장 주문이 있을 때__읽으면__빈 항목과 null 가능한 시각으로 주문을 보존한다`() {
        // given
        val header = purchase(9, "PENDING")
        header["created_at"] = null
        mapped.add(rows(header))
        extracted.add(rows())

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<Any>>()) } answers {
            mapRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<RowMapper<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
        }

        // when
        val result = reads.orders(7, request(0, 20))

        // then
        assertEquals(
            StoreMemberReads.Purchase(9L, "uuid9", 9007199254740993L, "PENDING", "주소", "READY", mutableListOf(), null),
            result.content.first(),
        )
        assertEquals(2, sql.size)
    }

    @Test
    fun `주문 페이지가 없을 때__읽으면__빈 성공을 돌려주고 항목 조회를 하지 않는다`() {
        // given
        mapped.add(rows())

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<Any>>()) } answers {
            mapRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<RowMapper<*>>())
        }

        // when
        val result = reads.orders(7, request(0, 20))

        // then
        assertEquals(listOf<StoreMemberReads.Purchase>(), result.content)
        assertEquals(1, sql.size)
    }

    @Test
    fun `저장된 장바구니가 없을 때__읽으면__생성 없이 빈 Optional을 돌려준다`() {
        // given
        extracted.add(rows())

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
        }

        // when
        val result = reads.cart(7)

        // then
        assertTrue(result.isEmpty)
        assertEquals(1, sql.size)
        assertMember(7)
    }

    @Test
    fun `비어 있는 저장 장바구니가 있을 때__읽으면__항목 없는 장바구니를 돌려준다`() {
        // given
        extracted.add(rows(map("cart_id", 8L, "user_id", 7L, "created_at", null, "item_id", null)))

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
        }

        // when
        val result = reads.cart(7)

        // then
        assertEquals(StoreMemberReads.Cart(8L, 7L, mutableListOf(), null), result.orElseThrow())
        assertMember(7)
    }

    @Test
    fun `현재 SKU와 사라진 SKU가 섞여 있을 때__장바구니를 읽으면__상품 SKU ID를 쓰고 사라진 항목과 원래 금액 계산을 보존한다`() {
        // given
        extracted.add(
            rows(
                map(
                    "cart_id", 8L, "user_id", 7L, "created_at", time, "item_id", 9L, "product_sku_id", 90L,
                    "quantity", 2, "live_sku_id", 90L, "sku_code", "LIVE", "price", Long.MAX_VALUE,
                    "product_name", "현재상품",
                ),
                map(
                    "cart_id", 8L, "user_id", 7L, "created_at", time, "item_id", 10L, "product_sku_id", 91L,
                    "quantity", 3, "live_sku_id", null, "sku_code", "MUST-NOT-LEAK", "price", 99L,
                    "product_name", "MUST-NOT-LEAK",
                ),
            ),
        )

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
            extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
        }

        // when
        val result = reads.cart(7)

        // then
        assertEquals(
            StoreMemberReads.Cart(
                8L,
                7L,
                mutableListOf(
                    StoreMemberReads.CartItem(9L, 90L, "현재상품", "LIVE", Long.MAX_VALUE, 2, -2L),
                    StoreMemberReads.CartItem(10L, 91L, null, null, 0L, 3, 0L),
                ),
                time,
            ),
            result.orElseThrow(),
        )
        assertTrue(sql.first().contains("ON s.id=i.product_sku_id"))
        assertFalse(sql.first().contains("i.sku_id"))
        assertMember(7)
        assertFalse(active)
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun `지원하는 스칼라 정렬일 때__읽으면__고정된 컬럼과 방향과 JPA 문자열 대소문자 무시를 쓴다`(
        wishlist: Boolean,
        order: List<StoreMemberReads.Order>,
        expected: String,
    ) {
        // given
        val query = StoreMemberReads.Request(0, 20, order)

        every { tx.execute(any<TransactionCallback<Any>>()) } answers {
            runInTransaction(firstArg<TransactionCallback<*>>())
        }
        if (wishlist) {
            extracted.add(rows())
            every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } answers {
                extractRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<ResultSetExtractor<*>>())
            }
        } else {
            mapped.add(rows())
            every { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<Any>>()) } answers {
                mapRows(firstArg<String>(), secondArg<Map<String, *>>(), thirdArg<RowMapper<*>>())
            }
        }

        // when
        if (wishlist) {
            reads.wishlists(7, query)
        } else {
            reads.orders(7, query)
        }

        // then
        assertTrue(sql.first().contains("$expected LIMIT"))
    }

    @ParameterizedTest
    @MethodSource("badSorts")
    fun `알 수 없거나 주입된 정렬일 때__읽으면__트랜잭션이나 데이터베이스 전에 거부한다`(
        wishlist: Boolean,
        property: String,
    ) {
        // given
        val request = StoreMemberReads.Request(0, 20, listOf(StoreMemberReads.Order(property, true, false)))

        // when
        assertThrows(IllegalArgumentException::class.java) {
            if (wishlist) {
                reads.wishlists(7, request)
            } else {
                reads.orders(7, request)
            }
        }

        // then
        verify {
            tx wasNot Called
            jdbc wasNot Called
        }
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `데이터베이스나 트랜잭션이 실패했을 때__읽으면__JDK API 실패로 노출하고 원래 원인을 보존한다`(
        operation: String,
        transaction: Boolean,
    ) {
        // given
        val failure: RuntimeException = if (transaction) {
            CannotCreateTransactionException("synthetic private details")
        } else {
            DataAccessResourceFailureException("synthetic private details")
        }

        if (transaction) {
            every { tx.execute(any<TransactionCallback<Any>>()) } throws failure
        } else {
            every { tx.execute(any<TransactionCallback<Any>>()) } answers {
                runInTransaction(firstArg<TransactionCallback<*>>())
            }
            every { jdbc.query(any<String>(), any<Map<String, *>>(), any<RowMapper<Any>>()) } throws failure
            every { jdbc.query(any<String>(), any<Map<String, *>>(), any<ResultSetExtractor<Any>>()) } throws failure
        }

        // when
        val unavailable = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (operation == "wishlist") {
                reads.wishlists(7, request(0, 20))
            } else if (operation == "orders") {
                reads.orders(7, request(0, 20))
            } else {
                reads.cart(7)
            }
        }

        // then
        assertSame(failure, unavailable.cause)
        assertEquals("Store member information could not be read", unavailable.message)
        assertFalse(active)
    }

    private fun runInTransaction(callback: TransactionCallback<*>): Any? {
        active = true
        try {
            return callback.doInTransaction(SimpleTransactionStatus())
        } finally {
            active = false
        }
    }

    private fun mapRows(query: String, bound: Map<String, *>, mapper: RowMapper<*>): List<Any?> {
        capture(query, bound)
        val rs = mapped.remove()
        val results = ArrayList<Any?>()
        var n = 0
        while (rs.next()) {
            results.add(mapper.mapRow(rs, n++))
        }
        return results
    }

    private fun extractRows(query: String, bound: Map<String, *>, extractor: ResultSetExtractor<*>): Any? {
        capture(query, bound)
        return extractor.extractData(extracted.remove())
    }

    private fun capture(query: String, bound: Map<String, *>) {
        assertTrue(active)
        sql.add(query)
        params.add(HashMap<String, Any?>(bound))
    }

    private fun assertMember(id: Long) {
        for (values in params) {
            assertEquals(id, values["member"])
        }
    }

    private fun purchase(id: Long, status: String): MutableMap<String, Any?> {
        return map(
            "id", id, "order_uuid", "uuid$id", "total_amount", 9007199254740993L,
            "status", status, "delivery_address", "주소", "delivery_status", "READY", "created_at", time,
        )
    }

    private fun wish(
        id: Long,
        sku: Long,
        status: String,
        category: Long?,
        categoryName: String?,
        option: Long?,
        group: String?,
        value: String?,
    ): MutableMap<String, Any?> {
        return map(
            "wishlist_id", id, "sku_id", sku, "sku_code", "SKU$sku", "product_id", sku + 80,
            "product_name", "상품$sku", "description", null, "image_url", null, "status", status,
            "price", 9007199254740993L, "category_id", category, "category_name", categoryName,
            "created_at", time, "option_value_id", option, "group_name", group, "value_name", value,
        )
    }

    companion object {
        @JvmStatic
        fun sorts(): Stream<Arguments> = Stream.of(
            Arguments.of(true, listOf(StoreMemberReads.Order("sku.product.name", true, true)), "ORDER BY lower(p.name) ASC"),
            Arguments.of(true, listOf(StoreMemberReads.Order("sku.price", false, true)), "ORDER BY s.price DESC"),
            Arguments.of(
                true,
                listOf(StoreMemberReads.Order("sku.product.category.name", false, false)),
                "ORDER BY c.name DESC",
            ),
            Arguments.of(
                false,
                listOf(StoreMemberReads.Order("receiverName", true, true)),
                "ORDER BY lower(o.receiver_name) ASC",
            ),
            Arguments.of(
                false,
                listOf(StoreMemberReads.Order("createdAt", false, false), StoreMemberReads.Order("id", true, false)),
                "ORDER BY o.created_at DESC,o.id ASC",
            ),
        )

        @JvmStatic
        fun badSorts(): Stream<Arguments> = Stream.of(
            Arguments.of(true, "id; DELETE FROM wishlists"),
            Arguments.of(false, "unknown"),
        )

        @JvmStatic
        fun failures(): Stream<Arguments> {
            return Stream.of("wishlist", "orders", "cart")
                .flatMap { op -> Stream.of(Arguments.of(op, false), Arguments.of(op, true)) }
        }

        private fun request(page: Int, size: Int): StoreMemberReads.Request {
            return StoreMemberReads.Request(page, size, listOf(StoreMemberReads.Order("createdAt", false, false)))
        }

        private fun map(vararg cells: Any?): MutableMap<String, Any?> {
            val row = HashMap<String, Any?>()
            var i = 0
            while (i < cells.size) {
                row[cells[i] as String] = cells[i + 1]
                i += 2
            }
            return row
        }

        private fun rows(vararg rows: Map<String, Any?>): ResultSet {
            val i = AtomicInteger(-1)
            val handler = InvocationHandler { _, method, args ->
                val name = method.name
                if (name == "next") {
                    return@InvocationHandler i.incrementAndGet() < rows.size
                }
                if (name.startsWith("get") && args != null && args[0] is String) {
                    val value = rows[i.get()][args[0] as String]
                    return@InvocationHandler when (name) {
                        "getLong" -> if (value == null) 0L else (value as Number).toLong()
                        "getInt" -> if (value == null) 0 else (value as Number).toInt()
                        else -> value
                    }
                }
                when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
            return Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf<Class<*>>(ResultSet::class.java),
                handler,
            ) as ResultSet
        }
    }
}
