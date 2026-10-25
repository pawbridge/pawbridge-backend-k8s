package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.stubbing.Answer
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionOperations
import java.util.Collections
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream

class JpaDirectOrdersTest {
    private lateinit var f: DirectOrderFixture

    @BeforeEach
    fun prepare() {
        f = DirectOrderFixture()
    }

    @AfterEach
    fun close() {
        f.close()
    }

    @Test
    fun given__active_member_and_locked_stock__when__buying_then_reading_own_order__then__preserve_snapshot_and_full_response_with_transactional_followups() {
        val created = f.service.create(7, f.command(2))

        assertEquals(
            DirectOrders.OrderDetails(
                33L, created.orderUuid, 7L, 200L, "PENDING", "READY", "fixture receiver", "fixture phone",
                "fixture address", "fixture message", f.time,
                listOf(DirectOrders.Item("fixture product", "CURRENT-80", 100L, 2)),
            ),
            created,
        )
        UUID.fromString(created.orderUuid)
        assertEquals(3, f.target.stockQuantity)
        assertEquals(8, f.sibling.stockQuantity)
        assertEquals(
            listOf(
                "begin:writer", "actor:write", "reference", "product-lock", "all-sku-lock", "search", "sku-read",
                "order-save", "commit:writer",
            ),
            f.events,
        )
        assertEquals(81L, f.searchParameters.first()["skuId"])
        assertEquals("nori-9.12.3-v1", f.searchParameters.first()["version"])
        f.product.updateName("later product name")
        f.target.updatePrice(999L)
        assertEquals(created, f.service.get(7, 33))
        assertEquals(listOf("begin:reader", "actor:read", "order-read", "commit:reader"), f.events.subList(9, 13))
        val stored = f.orders[33L]!!
        assertSame(stored, stored.orderItems.first().order)
    }

    @ParameterizedTest
    @MethodSource("actors")
    fun given__ineligible_current_actor__when__accessing_order_flow__then__reject_before_store_rows_or_followups(
        state: String,
        operation: String,
    ) {
        f.actor = when (state) {
            "null" -> null
            "mismatch" -> StoreOrderActors.Actor(8, true, false)
            "inactive" -> StoreOrderActors.Actor(7, false, false)
            else -> StoreOrderActors.Actor(7, true, true)
        }

        assertThrows(OrderActorRejectedException::class.java) {
            if (operation == "create") {
                f.service.create(7, f.command(1))
            } else {
                f.service.get(7, 33)
            }
        }

        verifyNoInteractions(f.products, f.skus, f.writerOrders, f.readerOrders, f.jdbc)
        if (operation == "create") {
            verify(f.actors).currentForWrite(7)
            verify(f.actors, never()).currentForRead(anyLong())
        } else {
            verify(f.actors).currentForRead(7)
            verify(f.actors, never()).currentForWrite(anyLong())
        }
        assertEquals("rollback:" + (if (operation == "create") "writer" else "reader"), f.events.last())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun given__nonpositive_quantity__when__buying__then__reject_and_rollback_before_stock_queries(quantity: Int) {
        assertThrows(IllegalArgumentException::class.java) { f.service.create(7, f.command(quantity)) }

        verifyNoInteractions(f.products, f.skus, f.writerOrders, f.jdbc)
        assertEquals("rollback:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-sku", "missing-product", "hidden", "sold-out", "deleted", "insufficient-stock"])
    fun given__source_stock_rejection__when__buying__then__preserve_failure_without_order_or_snapshot(kind: String) {
        if (kind == "missing-sku") {
            doReturn(listOf<Any>()).`when`(f.skus).findProductIdsBySkuIdIn(anyCollection())
        }
        if (kind == "missing-product") {
            doReturn(Optional.empty<Any>()).`when`(f.products).findByIdWithLock(90L)
        }
        if (kind == "hidden") {
            f.product.updateStatus(ProductStatus.HIDDEN)
        }
        if (kind == "sold-out") {
            f.product.updateStatus(ProductStatus.SOLD_OUT)
        }
        if (kind == "deleted") {
            f.product.updateStatus(ProductStatus.DELETED)
        }
        val expected: Class<out RuntimeException> = if (kind.startsWith("missing")) {
            IllegalArgumentException::class.java
        } else {
            IllegalStateException::class.java
        }

        assertThrows(expected) { f.service.create(7, f.command(if (kind == "insufficient-stock") 6 else 1)) }

        assertEquals(5, f.target.stockQuantity)
        verifyNoInteractions(f.writerOrders, f.jdbc)
        assertEquals("rollback:writer", f.events.last())
    }

    @Test
    fun given__long_price_overflow__when__buying__then__request_rollback_of_stock_and_followups_and_never_save_order() {
        f.target.updatePrice(Long.MAX_VALUE)

        val failure = assertThrows(IllegalArgumentException::class.java) { f.service.create(7, f.command(2)) }

        assertInstanceOf(ArithmeticException::class.java, failure.cause)
        assertEquals("rollback:writer", f.events.last())
        assertEquals(1, f.searchParameters.size)
        verifyNoInteractions(f.writerOrders)
        // This unit verifies the rollback request, not physical database restoration; Root owns the PG proof.
    }

    @ParameterizedTest
    @ValueSource(strings = ["search", "order"])
    fun given__projection_or_order_failure__when__buying__then__propagate_and_request_one_writer_rollback(stage: String) {
        f.failAt = stage

        assertThrows(DataAccessResourceFailureException::class.java) { f.service.create(7, f.command(1)) }

        assertEquals("rollback:writer", f.events.last())
        assertFalse(f.events.contains("commit:writer"))
        if (stage != "order") {
            verifyNoInteractions(f.writerOrders)
        }
    }

    @Test
    fun given__commit_failure__when__creating__then__propagate_without_returning_created_success() {
        f.failAt = "commit"

        assertThrows(TransactionSystemException::class.java) { f.service.create(7, f.command(1)) }

        assertEquals("commit:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun given__wrong_owner_or_missing_order__when__reading__then__source_bad_input_without_writer_work(missing: Boolean) {
        if (!missing) {
            val order = Order.builder().userId(8L).orderUuid("other").totalAmount(1L).build()
            DirectOrderFixture.id(order, 33)
            f.orders[33L] = order
        }

        assertThrows(IllegalArgumentException::class.java) { f.service.get(7, 33) }

        verifyNoInteractions(f.writerOrders, f.products, f.skus, f.jdbc)
        assertEquals("rollback:reader", f.events.last())
    }

    @Test
    fun given__a_repeated_direct_purchase__when__creating_twice__then__keep_source_new_order_semantics_without_an_idempotency_key() {
        val first = f.service.create(7, f.command(1))
        val second = f.service.create(7, f.command(1))

        assertNotEquals(first.orderId, second.orderId)
        assertNotEquals(first.orderUuid, second.orderUuid)
        assertEquals(3, f.target.stockQuantity)
        assertEquals(2, f.orders.size)
        assertEquals(2, f.searchParameters.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["write-in-ro", "read-in-rw"])
    fun given__miswired_transaction_mode__when__accessing__then__fail_closed_before_actor_or_store_rows(kind: String) {
        if (kind == "write-in-ro") {
            f.write.isReadOnly = true
        } else {
            f.read.isReadOnly = false
        }

        assertThrows(IllegalStateException::class.java) {
            if (kind == "write-in-ro") {
                f.service.create(7, f.command(1))
            } else {
                f.service.get(7, 33)
            }
        }

        verifyNoInteractions(f.actors, f.products, f.skus, f.writerOrders, f.readerOrders, f.jdbc)
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "get"])
    fun given__no_actual_transaction__when__accessing__then__reject_before_actor_or_store_rows(operation: String) {
        val absent = TransactionOperations.withoutTransaction()
        val service = JpaDirectOrders(f.writerOrders, f.readerOrders, f.skus, f.stocks, f.actors, absent, absent)

        assertThrows(IllegalStateException::class.java) {
            if (operation == "create") {
                service.create(7, f.command(1))
            } else {
                service.get(7, 33)
            }
        }

        verifyNoInteractions(f.actors, f.products, f.skus, f.writerOrders, f.readerOrders, f.jdbc)
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(OrderStatus::class)
    fun given__optional_status_and_own_snapshot__when__listing_then_reading_detail__then__reuse_owner_query_ro_actor_and_identical_snapshot(
        status: OrderStatus?,
    ) {
        val order = historyOrder(status ?: OrderStatus.PENDING)
        val query = StoreMemberReads.Request(1, 10, listOf())
        val page = Answer<Page<Order>> { call ->
            f.reader()
            f.events.add("history-page")
            val pageable = call.getArgument<Pageable>(call.arguments.size - 1)
            assertEquals(1, pageable.pageNumber)
            assertEquals(10, pageable.pageSize)
            assertTrue(pageable.sort.isUnsorted)
            PageImpl(listOf(order), pageable, 11)
        }
        if (status == null) {
            `when`(f.readerOrders.findByUserIdOrderByCreatedAtDesc(eq(7L), any())).thenAnswer(page)
        } else {
            `when`(f.readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(eq(7L), eq(status), any())).thenAnswer(page)
        }
        `when`(f.readerOrders.findWithItemsByUserIdAndIdIn(7L, listOf(77L))).thenAnswer {
            f.reader()
            f.events.add("history-items")
            listOf(order)
        }

        val result = f.service.list(7, status?.name, query)

        assertEquals(11L, result.totalElements)
        assertEquals(1, result.number)
        assertEquals(10, result.size)
        assertTrue(result.sort.isEmpty())
        assertEquals(listOf("begin:reader", "actor:read", "history-page", "history-items", "commit:reader"), f.events)
        f.product.updateName("later live product")
        f.target.updatePrice(999L)
        assertEquals(result.content.first(), f.service.get(7, 77))
        assertEquals("purchased product", result.content.first().items!!.first().productName)
        assertEquals("PURCHASED-80", result.content.first().items!!.first().skuCode)
        assertEquals(17L, result.content.first().items!!.first().price)
        if (status == null) {
            verify(f.readerOrders, never()).findByUserIdAndStatusOrderByCreatedAtDesc(anyLong(), any(), any())
        } else {
            verify(f.readerOrders, never()).findByUserIdOrderByCreatedAtDesc(anyLong(), any())
        }
        verify(f.readerOrders).findWithItemsByUserIdAndIdIn(7L, listOf(77L))
        verifyNoInteractions(f.writerOrders, f.skus, f.products, f.jdbc)
        verify(f.actors, never()).currentForWrite(anyLong())
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "mismatch", "inactive", "deleted"])
    fun given__missing_or_ineligible_current_member__when__listing__then__reject_before_any_order_query(state: String) {
        f.actor = when (state) {
            "null" -> null
            "mismatch" -> StoreOrderActors.Actor(8, true, false)
            "inactive" -> StoreOrderActors.Actor(7, false, false)
            else -> StoreOrderActors.Actor(7, true, true)
        }

        assertThrows(OrderActorRejectedException::class.java) {
            f.service.list(7, null, StoreMemberReads.Request(0, 10, listOf()))
        }

        verifyNoInteractions(f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
        assertEquals(listOf("begin:reader", "actor:read", "rollback:reader"), f.events)
    }

    @ParameterizedTest
    @MethodSource("invalidHistoryPages")
    fun given__oversized_or_recursive_or_large_offset_query__when__listing__then__reject_before_transaction_or_actor(
        request: StoreMemberReads.Request,
    ) {
        assertThrows(IllegalArgumentException::class.java) { f.service.list(7, null, request) }

        assertTrue(f.events.isEmpty())
        verifyNoInteractions(f.actors, f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @Test
    fun given__valid_scalar_sorts__when__listing_empty_page__then__preserve_order_direction_and_ignore_case_without_relationship_paths() {
        val sort = listOf(
            StoreMemberReads.Order("totalAmount", true, false),
            StoreMemberReads.Order("receiverName", false, true),
        )
        `when`(f.readerOrders.findByUserIdOrderByCreatedAtDesc(eq(7L), any())).thenAnswer { call ->
            val page = call.getArgument<Pageable>(1)
            assertEquals("totalAmount", page.sort.toList().first().property)
            assertTrue(page.sort.getOrderFor("totalAmount")!!.isAscending)
            assertTrue(page.sort.getOrderFor("receiverName")!!.isIgnoreCase)
            PageImpl(listOf<Order>(), page, 0)
        }

        val result = f.service.list(7, null, StoreMemberReads.Request(0, 100, sort))

        assertTrue(result.content.isEmpty())
        assertEquals(0L, result.totalElements)
        assertEquals(100, result.size)
        assertEquals(sort, result.sort)
        verify(f.readerOrders, never()).findWithItemsByUserIdAndIdIn(anyLong(), anyList())
        verifyNoInteractions(f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @ParameterizedTest
    @ValueSource(strings = ["no-tx", "read-in-rw"])
    fun given__miswired_history_read_transaction__when__listing__then__fail_before_actor_or_store_rows(kind: String) {
        val reads: TransactionOperations = if (kind == "no-tx") TransactionOperations.withoutTransaction() else f.write
        val service = JpaDirectOrders(f.writerOrders, f.readerOrders, f.skus, f.stocks, f.actors, f.write, reads)

        assertThrows(IllegalStateException::class.java) {
            service.list(7, null, StoreMemberReads.Request(0, 10, listOf()))
        }

        verifyNoInteractions(f.actors, f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @Test
    fun given__history_query_failure__when__listing__then__propagate_and_rollback_read_transaction_without_writes() {
        `when`(f.readerOrders.findByUserIdOrderByCreatedAtDesc(eq(7L), any())).thenAnswer {
            f.reader()
            f.events.add("history-page")
            throw DataAccessResourceFailureException("private history query")
        }

        assertThrows(DataAccessResourceFailureException::class.java) {
            f.service.list(7, null, StoreMemberReads.Request(0, 10, listOf()))
        }

        assertEquals(listOf("begin:reader", "actor:read", "history-page", "rollback:reader"), f.events)
        verifyNoInteractions(f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @Test
    fun given__nonempty_parent_page_and_different_bulk_result_order__when__listing__then__fetch_only_verified_owner_page_ids_once_and_preserve_parent_order_and_metadata() {
        val first = historyOrder(77, OrderStatus.PAID)
        val second = historyOrder(88, OrderStatus.PAID)
        val sort = listOf(StoreMemberReads.Order("totalAmount", true, false))
        `when`(f.readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(eq(7L), eq(OrderStatus.PAID), any())).thenAnswer { call ->
            f.reader()
            f.events.add("history-page")
            PageImpl(listOf(second, first), call.getArgument<Pageable>(2), 6)
        }
        `when`(f.readerOrders.findWithItemsByUserIdAndIdIn(7L, listOf(88L, 77L))).thenAnswer {
            f.reader()
            f.events.add("history-items")
            listOf(first, second)
        }

        val result = f.service.list(7, "PAID", StoreMemberReads.Request(1, 2, sort))

        assertEquals(listOf(88L, 77L), result.content.map { it.orderId })
        assertEquals(1, result.number)
        assertEquals(2, result.size)
        assertEquals(6L, result.totalElements)
        assertEquals(sort, result.sort)
        assertEquals(listOf("begin:reader", "actor:read", "history-page", "history-items", "commit:reader"), f.events)
        verify(f.readerOrders).findWithItemsByUserIdAndIdIn(7L, listOf(88L, 77L))
        verify(f.readerOrders, never()).findByUserIdOrderByCreatedAtDesc(anyLong(), any())
        verifyNoInteractions(f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @Test
    fun given__page_item_bulk_query_failure__when__listing__then__propagate_and_rollback_same_read_transaction_without_writes() {
        val order = historyOrder(OrderStatus.PENDING)
        `when`(f.readerOrders.findByUserIdOrderByCreatedAtDesc(eq(7L), any())).thenAnswer { call ->
            f.reader()
            f.events.add("history-page")
            PageImpl(listOf(order), call.getArgument<Pageable>(1), 1)
        }
        `when`(f.readerOrders.findWithItemsByUserIdAndIdIn(7L, listOf(77L))).thenAnswer {
            f.reader()
            f.events.add("history-items")
            throw DataAccessResourceFailureException("private bulk query")
        }

        assertThrows(DataAccessResourceFailureException::class.java) {
            f.service.list(7, null, StoreMemberReads.Request(0, 10, listOf()))
        }

        assertEquals(listOf("begin:reader", "actor:read", "history-page", "history-items", "rollback:reader"), f.events)
        verify(f.readerOrders).findWithItemsByUserIdAndIdIn(7L, listOf(77L))
        verifyNoInteractions(f.writerOrders, f.products, f.skus, f.jdbc)
    }

    private fun historyOrder(status: OrderStatus): Order {
        return historyOrder(77, status)
    }

    private fun historyOrder(orderId: Long, status: OrderStatus): Order {
        val order = Order.builder()
            .userId(7L)
            .orderUuid("owned-history-uuid")
            .totalAmount(34L)
            .receiverName("snapshot receiver")
            .receiverPhone("snapshot phone")
            .deliveryAddress("snapshot address")
            .deliveryMessage("snapshot message")
            .build()
        order.updateStatus(status)
        DirectOrderFixture.id(order, orderId)
        ReflectionTestUtils.setField(order, "createdAt", f.time)
        order.orderItems.add(
            OrderItem.builder()
                .order(order)
                .productSKU(f.target)
                .productName("purchased product")
                .skuCode("PURCHASED-80")
                .price(17L)
                .quantity(2)
                .build(),
        )
        f.orders[orderId] = order
        return order
    }

    companion object {
        @JvmStatic
        fun actors(): Stream<Arguments> {
            return Stream.of("null", "mismatch", "inactive", "deleted")
                .flatMap { state -> Stream.of("create", "get").map { operation -> Arguments.of(state, operation) } }
        }

        @JvmStatic
        fun invalidHistoryPages(): Stream<Arguments> = Stream.of(
            Arguments.of(StoreMemberReads.Request(0, 101, listOf())),
            Arguments.of(StoreMemberReads.Request(Int.MAX_VALUE, 100, listOf())),
            Arguments.of(
                StoreMemberReads.Request(
                    0,
                    10,
                    listOf(StoreMemberReads.Order("orderItems.order.orderItems.order.id", true, false)),
                ),
            ),
            Arguments.of(StoreMemberReads.Request(0, 10, listOf(StoreMemberReads.Order("unknown", true, false)))),
            Arguments.of(
                StoreMemberReads.Request(0, 10, Collections.nCopies(5, StoreMemberReads.Order("id", true, false))),
            ),
        )
    }
}
