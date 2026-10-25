package com.pawbridge.backend.store.internal.memberread.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException
import com.pawbridge.backend.store.api.StoreMemberReads
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
import org.mockito.ArgumentMatchers.eq
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
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.Queue
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class JdbcStoreMemberReadsTest {
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    private val tx: TransactionOperations = mock(TransactionOperations::class.java)
    private val reads = JdbcStoreMemberReads(jdbc, tx)
    private val mapped: Queue<ResultSet> = java.util.ArrayDeque()
    private val extracted: Queue<ResultSet> = java.util.ArrayDeque()
    private val sql = ArrayList<String>()
    private val params = ArrayList<Map<String, *>>()
    private var active = false
    private var count = 3L
    private val time: LocalDateTime = LocalDateTime.of(2026, 10, 20, 3, 4, 5, 123456000)

    @BeforeEach
    fun setup() {
        `when`(tx.execute(any<TransactionCallback<Any>>())).thenAnswer { call ->
            active = true
            try {
                call.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
            } finally {
                active = false
            }
        }
        `when`(jdbc.query(anyString(), anyMap<String, Any>(), any<RowMapper<Any>>())).thenAnswer { call ->
            capture(call.getArgument<String>(0), call.getArgument<Map<String, *>>(1))
            val rs = mapped.remove()
            val mapper = call.getArgument<RowMapper<*>>(2)
            val results = ArrayList<Any?>()
            var n = 0
            while (rs.next()) {
                results.add(mapper.mapRow(rs, n++))
            }
            results
        }
        `when`(jdbc.query(anyString(), anyMap<String, Any>(), any<ResultSetExtractor<Any>>())).thenAnswer { call ->
            capture(call.getArgument<String>(0), call.getArgument<Map<String, *>>(1))
            call.getArgument<ResultSetExtractor<*>>(2).extractData(extracted.remove())
        }
        `when`(jdbc.queryForObject(anyString(), anyMap<String, Any>(), eq(Long::class.javaObjectType))).thenAnswer { call ->
            capture(call.getArgument<String>(0), call.getArgument<Map<String, *>>(1))
            count
        }
    }

    @Test
    fun given__wishlist_page_and_options__when__reading__then__preserve_member_scope_parent_order_all_statuses_and_source_fields() {
        extracted.add(
            rows(
                wish(11L, 21L, "HIDDEN", null, null, null, null, null),
                wish(12L, 22L, "DELETED", 8L, "사료", 3L, "색상", "파랑"),
                wish(12L, 22L, "DELETED", 8L, "사료", 4L, "크기", "대"),
            ),
        )

        val result = reads.wishlists(7, request(0, 2))

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
        verify(tx, times(1)).execute(any<TransactionCallback<Any>>())
    }

    @Test
    fun given__no_first_page_wishlist__when__reading__then__empty_page_without_detail_or_count() {
        extracted.add(rows())

        assertEquals(
            StoreMemberReads.Page<StoreMemberReads.Wishlist>(listOf(), 0, 20, 0L, request(0, 20).sort()),
            reads.wishlists(19, request(0, 20)),
        )

        assertEquals(1, sql.size)
        assertMember(19)
    }

    @Test
    fun given__later_empty_wishlist_page__when__reading__then__retain_total_count_and_unsorted_request() {
        count = 2
        extracted.add(rows())
        val request = StoreMemberReads.Request(3, 2, listOf())

        val result = reads.wishlists(7, request)

        assertEquals(2L, result.totalElements)
        assertEquals(listOf<StoreMemberReads.Order>(), result.sort)
        assertFalse(sql.first().contains("ORDER BY"))
        assertEquals(6L, params.first()["offset"])
        assertEquals(2, sql.size)
    }

    @Test
    fun given__partial_later_wishlist_page__when__reading__then__derive_total_without_count() {
        extracted.add(rows(wish(11L, 21L, "ACTIVE", null, null, null, null, null)))

        assertEquals(5L, reads.wishlists(7, request(2, 2)).totalElements)
        assertEquals(1, sql.size)
    }

    @Test
    fun given__two_order_headers_and_out_of_order_item_rows__when__reading__then__preserve_order_snapshots_and_page_boundaries() {
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

        val result = reads.orders(7, request(0, 2))

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
    fun given__persisted_order_without_items__when__reading__then__preserve_order_with_empty_items_and_nullable_time() {
        val header = purchase(9, "PENDING")
        header["created_at"] = null
        mapped.add(rows(header))
        extracted.add(rows())

        assertEquals(
            StoreMemberReads.Purchase(9L, "uuid9", 9007199254740993L, "PENDING", "주소", "READY", mutableListOf(), null),
            reads.orders(7, request(0, 20)).content.first(),
        )
        assertEquals(2, sql.size)
    }

    @Test
    fun given__absent_order_page__when__reading__then__empty_success_and_no_item_query() {
        mapped.add(rows())

        assertEquals(listOf<StoreMemberReads.Purchase>(), reads.orders(7, request(0, 20)).content)
        assertEquals(1, sql.size)
    }

    @Test
    fun given__no_persisted_cart__when__reading__then__optional_empty_without_creation() {
        extracted.add(rows())

        assertTrue(reads.cart(7).isEmpty)
        assertEquals(1, sql.size)
        assertMember(7)
    }

    @Test
    fun given__empty_persisted_cart__when__reading__then__present_cart_with_empty_items() {
        extracted.add(rows(map("cart_id", 8L, "user_id", 7L, "created_at", null, "item_id", null)))

        assertEquals(StoreMemberReads.Cart(8L, 7L, mutableListOf(), null), reads.cart(7).orElseThrow())
        assertMember(7)
    }

    @Test
    fun given__live_and_missing_cart_sku__when__reading__then__use_product_sku_id_and_preserve_missing_item_and_original_money_math() {
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
            reads.cart(7).orElseThrow(),
        )
        assertTrue(sql.first().contains("ON s.id=i.product_sku_id"))
        assertFalse(sql.first().contains("i.sku_id"))
        assertMember(7)
        assertFalse(active)
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun given__supported_scalar_sort__when__reading__then__use_fixed_column_direction_and_jpa_string_ignore_case(
        wishlist: Boolean,
        order: List<StoreMemberReads.Order>,
        expected: String,
    ) {
        if (wishlist) {
            extracted.add(rows())
        } else {
            mapped.add(rows())
        }
        val query = StoreMemberReads.Request(0, 20, order)

        if (wishlist) {
            reads.wishlists(7, query)
        } else {
            reads.orders(7, query)
        }

        assertTrue(sql.first().contains("$expected LIMIT"))
    }

    @ParameterizedTest
    @MethodSource("badSorts")
    fun given__unknown_or_injected_sort__when__reading__then__reject_before_transaction_or_database(
        wishlist: Boolean,
        property: String,
    ) {
        val request = StoreMemberReads.Request(0, 20, listOf(StoreMemberReads.Order(property, true, false)))

        assertThrows(IllegalArgumentException::class.java) {
            if (wishlist) {
                reads.wishlists(7, request)
            } else {
                reads.orders(7, request)
            }
        }

        verifyNoInteractions(tx, jdbc)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__database_or_transaction_failure__when__reading__then__expose_jdk_api_failure_and_original_cause(
        operation: String,
        transaction: Boolean,
    ) {
        val failure: RuntimeException = if (transaction) {
            CannotCreateTransactionException("synthetic private details")
        } else {
            DataAccessResourceFailureException("synthetic private details")
        }
        if (transaction) {
            doThrow(failure).`when`(tx).execute(any<TransactionCallback<Any>>())
        } else {
            doThrow(failure).`when`(jdbc).query(anyString(), anyMap<String, Any>(), any<RowMapper<Any>>())
            doThrow(failure).`when`(jdbc).query(anyString(), anyMap<String, Any>(), any<ResultSetExtractor<Any>>())
        }

        val unavailable = assertThrows(StoreMemberReadUnavailableException::class.java) {
            if (operation == "wishlist") {
                reads.wishlists(7, request(0, 20))
            } else if (operation == "orders") {
                reads.orders(7, request(0, 20))
            } else {
                reads.cart(7)
            }
        }

        assertSame(failure, unavailable.cause)
        assertEquals("Store member information could not be read", unavailable.message)
        assertFalse(active)
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
            return mock(
                ResultSet::class.java,
                Answer<Any?> { call ->
                    val method = call.method.name
                    if (method == "next") {
                        return@Answer i.incrementAndGet() < rows.size
                    }
                    if (method.startsWith("get") && call.getArgument<Any?>(0) is String) {
                        val column = call.getArgument<String>(0)
                        val value = rows[i.get()][column]
                        return@Answer when (method) {
                            "getLong" -> if (value == null) 0L else (value as Number).toLong()
                            "getInt" -> if (value == null) 0 else (value as Number).toInt()
                            else -> value
                        }
                    }
                    RETURNS_DEFAULTS.answer(call)
                },
            )
        }
    }
}
