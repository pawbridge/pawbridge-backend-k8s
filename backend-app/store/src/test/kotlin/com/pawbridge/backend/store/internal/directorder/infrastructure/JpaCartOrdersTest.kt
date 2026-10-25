package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository.CartRepository
import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionOperations
import java.util.Optional
import java.util.UUID

class JpaCartOrdersTest {
    private lateinit var f: DirectOrderFixture
    private lateinit var carts: CartRepository
    private lateinit var cart: Cart
    private lateinit var service: JpaCartOrders
    private val command = CartOrders.Command("fixture receiver", "fixture phone", "fixture address", "fixture message")

    @BeforeEach
    fun setup() {
        f = DirectOrderFixture()
        carts = mock(CartRepository::class.java)
        cart = spy(Cart.builder().userId(7L).build())
        doAnswer {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }.`when`(carts).findByUserId(7L)
        doAnswer { call ->
            f.writer()
            f.events.add("cart-clear")
            call.callRealMethod()
        }.`when`(cart).clearItems()
        doAnswer { call ->
            f.writer()
            f.events.add("reference")
            val ids: Collection<Long?> = call.getArgument(0)
            listOf(80L, 81L).filter { ids.contains(it) }.map { id ->
                val ref = mock(ProductSKURepository.SkuProductId::class.java)
                `when`(ref.skuId).thenReturn(id)
                `when`(ref.productId).thenReturn(90L)
                ref
            }
        }.`when`(f.skus).findProductIdsBySkuIdIn(anyCollection())
        doAnswer { call ->
            f.writer()
            f.events.add("sku-read-all")
            if (f.failAt == "read") {
                throw DirectOrderFixture.failure()
            }
            val ids: Collection<Long?> = call.getArgument(0)
            listOf(f.target, f.sibling).filter { sku -> ids.contains(sku.id) }
        }.`when`(f.skus).findAllById(any())
        service = JpaCartOrders(carts, f.writerOrders, f.skus, f.stocks, f.actors, f.write)
    }

    @AfterEach
    fun close() {
        f.close()
    }

    private fun item(id: Long, quantity: Int) {
        cart.addItem(CartItem.builder().skuId(id).quantity(quantity).build())
    }

    @Test
    fun given__two_live_cart_skus__when__checking_out__then__one_transaction_preserves_original_order_and_stock_followups_and_clears_cart() {
        item(80, 2)
        item(81, 3)

        val result = service.create(7, command)

        assertEquals(350L, result.totalAmount)
        assertEquals(7L, result.userId)
        assertEquals("PENDING", result.status)
        assertEquals("READY", result.deliveryStatus)
        assertEquals("fixture receiver", result.receiverName)
        assertEquals("fixture phone", result.receiverPhone)
        assertEquals("fixture address", result.deliveryAddress)
        assertEquals("fixture message", result.deliveryMessage)
        assertEquals(f.time, result.createdAt)
        UUID.fromString(result.orderUuid)
        assertEquals(2, result.items!!.size)
        assertEquals(100L, result.items!!.first().price)
        assertEquals(2, result.items!!.first().quantity)
        assertEquals(50L, result.items!![1].price)
        assertEquals(3, f.target.stockQuantity)
        assertEquals(5, f.sibling.stockQuantity)
        assertTrue(cart.items.isEmpty())
        assertEquals(1, f.searchParameters.size)
        assertEquals(1, f.orders.size)
        assertEquals(
            listOf(
                "begin:writer", "actor:write", "cart", "reference", "reference", "product-lock", "all-sku-lock",
                "search", "sku-read-all", "order-save", "cart-clear", "commit:writer",
            ),
            f.events,
        )
        verify(f.skus, never()).findById(anyLong())
        verify(f.actors, never()).currentForRead(anyLong())
        verifyNoInteractions(f.readerOrders)
        val saved = f.orders[result.orderId!!]!!
        for (row in saved.orderItems) {
            assertSame(saved, row.order)
        }
    }

    @Test
    fun given__price_changed_before_product_lock_acquired__when__checking_out__then__total_and_items_use_the_same_locked_current_price() {
        item(80, 2)
        doAnswer {
            f.writer()
            f.events.add("product-lock")
            f.target.updatePrice(200L)
            Optional.of(f.product)
        }.`when`(f.products).findByIdWithLock(90L)

        val result = service.create(7, command)

        assertEquals(400L, result.totalAmount)
        assertEquals(200L, result.items!!.first().price)
        assertTrue(f.events.indexOf("all-sku-lock") < f.events.indexOf("sku-read-all"))
        verify(f.skus, never()).findById(anyLong())
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "wrong", "inactive", "deleted"])
    fun given__ineligible_current_actor__when__checking_out__then__reject_before_cart_or_store_rows(kind: String) {
        f.actor = when (kind) {
            "null" -> null
            "wrong" -> StoreOrderActors.Actor(8, true, false)
            "inactive" -> StoreOrderActors.Actor(7, false, false)
            else -> StoreOrderActors.Actor(7, true, true)
        }

        assertThrows(OrderActorRejectedException::class.java) { service.create(7, command) }

        verifyNoInteractions(carts, f.products, f.skus, f.writerOrders, f.jdbc)
        assertEquals("rollback:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "empty", "only-stale"])
    fun given__no_visible_cart_items__when__checking_out__then__original_empty_error_without_consumption_or_stock_work(
        kind: String,
    ) {
        if (kind == "missing") {
            doReturn(Optional.empty<Cart>()).`when`(carts).findByUserId(7L)
        }
        if (kind == "only-stale") {
            item(999, 2)
        }

        val failure = assertThrows(IllegalArgumentException::class.java) { service.create(7, command) }

        assertEquals("Cart is empty", failure.message)
        verify(cart, never()).clearItems()
        verifyNoInteractions(f.products, f.writerOrders, f.jdbc)
        if (kind == "only-stale") {
            assertEquals(1, cart.items.size)
        }
    }

    @Test
    fun given__live_and_stale_cart_items__when__checking_out__then__purchase_visible_items_and_clear_whole_cart_like_original() {
        item(80, 1)
        item(999, 3)

        val result = service.create(7, command)

        assertEquals(100L, result.totalAmount)
        assertEquals(1, result.items!!.size)
        assertTrue(cart.items.isEmpty())
        assertEquals(4, f.target.stockQuantity)
    }

    @Test
    fun given__duplicate_cart_sku__when__checking_out__then__fail_closed_without_implicit_merge_or_stock_change() {
        item(80, 1)
        item(80, 2)

        assertThrows(PersistenceException::class.java) { service.create(7, command) }

        verifyNoInteractions(f.skus, f.products, f.writerOrders)
        verify(cart, never()).clearItems()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun given__nonpositive_stored_quantity__when__checking_out__then__reuse_original_stock_validation(quantity: Int) {
        item(80, quantity)

        assertThrows(IllegalArgumentException::class.java) { service.create(7, command) }

        verifyNoInteractions(f.products, f.writerOrders, f.jdbc)
        verify(cart, never()).clearItems()
    }

    @ParameterizedTest
    @ValueSource(strings = ["inactive", "stock", "missing-under-lock"])
    fun given__original_stock_rejection__when__checking_out__then__rollback_without_consuming_cart(kind: String) {
        item(80, if (kind == "stock") 6 else 1)
        if (kind == "inactive") {
            f.product.updateStatus(ProductStatus.HIDDEN)
        }
        if (kind == "missing-under-lock") {
            doReturn(listOf(f.sibling)).`when`(f.skus).findAllByProductIdWithLock(90L)
        }
        val type: Class<out RuntimeException> = if (kind == "missing-under-lock") {
            IllegalArgumentException::class.java
        } else {
            IllegalStateException::class.java
        }

        assertThrows(type) { service.create(7, command) }

        assertEquals(5, f.target.stockQuantity)
        verify(cart, never()).clearItems()
        verifyNoInteractions(f.writerOrders)
        assertEquals("rollback:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["multiply", "sum"])
    fun given__order_amount_overflow__when__checking_out__then__bad_input_requests_rollback_of_stock_and_projection_without_consuming_cart(
        kind: String,
    ) {
        f.target.updatePrice(Long.MAX_VALUE)
        item(80, if (kind == "multiply") 2 else 1)
        if (kind == "sum") {
            f.sibling.updatePrice(1L)
            item(81, 1)
        }

        val failure = assertThrows(IllegalArgumentException::class.java) { service.create(7, command) }

        assertInstanceOf(ArithmeticException::class.java, failure.cause)
        assertEquals(1, f.searchParameters.size)
        verifyNoInteractions(f.writerOrders)
        verify(cart, never()).clearItems()
        assertEquals("rollback:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["cart", "search", "read", "order", "commit"])
    fun given__query_followup_order_or_commit_failure__when__checking_out__then__never_return_success(stage: String) {
        item(80, 1)
        f.failAt = stage
        val type: Class<out RuntimeException> = if (stage == "commit") {
            TransactionSystemException::class.java
        } else {
            DataAccessResourceFailureException::class.java
        }

        assertThrows(type) { service.create(7, command) }

        if (stage != "commit") {
            verify(cart, never()).clearItems()
            assertEquals(1, cart.items.size)
            assertEquals("rollback:writer", f.events.last())
        }
        // Mock persistence observes rollback requests; Root owns physical DB restoration after clear/commit failure.
    }

    @Test
    fun given__selected_sku_missing_after_locks__when__materializing_order__then__fixed_failure_without_consuming_cart() {
        item(80, 1)
        doReturn(listOf<Any>()).`when`(f.skus).findAllById(any())

        val failure = assertThrows(PersistenceException::class.java) { service.create(7, command) }

        assertEquals("Checkout items cannot be read", failure.message)
        verify(cart, never()).clearItems()
        verifyNoInteractions(f.writerOrders)
    }

    @Test
    fun given__already_consumed_cart_without_new_add__when__checking_out_again__then__empty_error_and_one_order_only() {
        item(80, 1)
        service.create(7, command)

        assertThrows(IllegalArgumentException::class.java) { service.create(7, command) }

        assertEquals(1, f.orders.size)
        assertEquals(4, f.target.stockQuantity)
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "read-only"])
    fun given__invalid_writer_transaction__when__checking_out__then__fail_before_actor_or_cart(kind: String) {
        val tx: TransactionOperations = if (kind == "none") TransactionOperations.withoutTransaction() else f.read
        val service = JpaCartOrders(carts, f.writerOrders, f.skus, f.stocks, f.actors, tx)

        assertThrows(IllegalStateException::class.java) { service.create(7, command) }

        verifyNoInteractions(carts, f.actors, f.skus, f.products, f.writerOrders)
    }
}
