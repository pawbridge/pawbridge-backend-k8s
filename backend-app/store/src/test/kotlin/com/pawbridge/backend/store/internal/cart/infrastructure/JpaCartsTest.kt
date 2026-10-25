package com.pawbridge.backend.store.internal.cart.infrastructure

import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException
import com.pawbridge.backend.store.internal.cart.application.Carts
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository.CartRepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import java.util.stream.Stream

class JpaCartsTest {
    val em: EntityManager = mock(EntityManager::class.java)
    val repository: CartRepository = mock(CartRepository::class.java)
    val actors: StoreOrderActors = mock(StoreOrderActors::class.java)
    val events = ArrayList<String>()
    val write = TransactionTemplate(Manager(false))
    val read = TransactionTemplate(Manager(true))
    val carts = JpaCarts(em, repository, write, read, actors)
    val product: Product = Product.builder().name("live product").imageUrl("unused image").status(ProductStatus.HIDDEN).build()
    val sku: ProductSKU = ProductSKU.builder().product(product).skuCode("LIVE-80").price(100L).stockQuantity(5).build()

    @Suppress("UNCHECKED_CAST")
    val query: TypedQuery<ProductSKU> = mock(TypedQuery::class.java) as TypedQuery<ProductSKU>
    var stored: Cart? = null
    var actor: StoreOrderActors.Actor? = StoreOrderActors.Actor(7, true, false)
    var failure = ""

    @BeforeEach
    fun prepare() {
        write.setName("cart-write")
        read.setName("cart-read")
        read.isReadOnly = true
        ReflectionTestUtils.setField(sku, "id", 80L)
        `when`(actors.currentForWrite(7)).thenAnswer {
            active(false)
            events.add("actor:write")
            actor
        }
        `when`(actors.currentForRead(7)).thenAnswer {
            active(true)
            events.add("actor:read")
            actor
        }
        `when`(repository.findByUserId(7L)).thenAnswer {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }
        `when`(repository.save(any(Cart::class.java))).thenAnswer { call ->
            active(false)
            events.add("save")
            stored = call.getArgument(0)
            stored
        }
        `when`(em.find(ProductSKU::class.java, 80L)).thenAnswer {
            active(false)
            events.add("sku")
            sku
        }
        `when`(em.createQuery(anyString(), eq(ProductSKU::class.java))).thenAnswer {
            active(true)
            events.add("sku-list")
            query
        }
        `when`(query.setParameter(eq("ids"), any<Any>())).thenReturn(query)
        `when`(query.resultList).thenAnswer {
            active(true)
            listOf(sku)
        }
    }

    @AfterEach
    fun clean() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    fun active(ro: Boolean) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertEquals(ro, TransactionSynchronizationManager.isCurrentTransactionReadOnly())
    }

    fun existing(quantity: Int): CartItem {
        val cart = Cart.builder().userId(7L).build()
        stored = cart
        val item = CartItem.builder().skuId(80L).quantity(quantity).build()
        cart.addItem(item)
        return item
    }

    @Test
    fun given__active_member__when__adding_twice_then_reading__then__serialize_actor_first_and_preserve_wire_and_per_add_stock_check() {
        carts.add(7, 80L, 3)
        carts.add(7, 80L, 3)

        assertEquals(6, stored!!.items.first().quantity)
        assertEquals(5, sku.stockQuantity)
        assertEquals(1, stored!!.items.size)
        assertEquals(80L, stored!!.items.first().productSkuId)
        assertSame(stored, stored!!.items.first().cart)
        assertEquals(
            listOf(
                "begin:write", "actor:write", "sku", "cart", "save", "commit:write",
                "begin:write", "actor:write", "sku", "cart", "commit:write",
            ),
            events,
        )
        assertEquals(listOf(Carts.Item(null, 80L, "LIVE-80", "live product", 100L, 6, 600L)), carts.get(7))
        verify(query).setParameter("ids", setOf(80L))
        verify(repository, times(1)).save(any(Cart::class.java))
    }

    @ParameterizedTest
    @ValueSource(strings = ["no-parent", "empty-parent", "missing-sku"])
    fun given__empty_or_stale_cart__when__reading__then__empty_array_without_creating_or_deleting(kind: String) {
        if (kind == "empty-parent") {
            stored = Cart.builder().userId(7L).build()
        }
        if (kind == "missing-sku") {
            existing(1)
            doReturn(listOf<Any>()).`when`(query).resultList
        }

        assertEquals(listOf<Carts.Item>(), carts.get(7))

        verify(repository, never()).save(any(Cart::class.java))
        if (kind != "missing-sku") {
            verifyNoInteractions(em, query)
        } else {
            assertEquals(1, stored!!.items.size)
        }
    }

    @ParameterizedTest
    @MethodSource("rejectedActors")
    fun given__invalid_current_actor__when__accessing_any_cart_operation__then__reject_before_store(state: String, op: String) {
        actor = when (state) {
            "null" -> null
            "wrong" -> StoreOrderActors.Actor(8, true, false)
            "inactive" -> StoreOrderActors.Actor(7, false, false)
            else -> StoreOrderActors.Actor(7, true, true)
        }

        assertThrows(CartActorRejectedException::class.java) { operation(carts, op) }

        verifyNoInteractions(em, repository, query)
        if (op == "get") {
            verify(actors).currentForRead(7)
            verify(actors, never()).currentForWrite(anyLong())
        } else {
            verify(actors).currentForWrite(7)
            verify(actors, never()).currentForRead(anyLong())
        }
        assertEquals("rollback:" + (if (op == "get") "read" else "write"), events.last())
    }

    @ParameterizedTest
    @MethodSource("badAdds")
    fun given__invalid_add_input__when__adding__then__bad_input_without_cart_or_sku_write(id: Long?, quantity: Int?) {
        assertThrows(IllegalArgumentException::class.java) { carts.add(7, id, quantity) }

        verifyNoInteractions(repository, em)
        assertEquals("rollback:write", events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "stock"])
    fun given__original_sku_rejection__when__adding__then__reject_before_creating_cart(kind: String) {
        if (kind == "missing") {
            doReturn(null).`when`(em).find(ProductSKU::class.java, 80L)
        }

        assertThrows(IllegalArgumentException::class.java) { carts.add(7, 80L, if (kind == "stock") 6 else 1) }

        verifyNoInteractions(repository)
        assertEquals("rollback:write", events.last())
    }

    @Test
    fun given__integer_max_existing_quantity__when__adding__then__reject_overflow_without_changing_item() {
        val item = existing(Int.MAX_VALUE)

        assertThrows(IllegalArgumentException::class.java) { carts.add(7, 80L, 1) }

        assertEquals(Int.MAX_VALUE, item.quantity)
        assertEquals("rollback:write", events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["add", "update", "get"])
    fun given__long_price_overflow__when__accessing_cart__then__reject_with_transaction_rollback(op: String) {
        val item = existing(2)
        sku.updatePrice(Long.MAX_VALUE)

        assertThrows(IllegalArgumentException::class.java) {
            if (op == "add") {
                carts.add(7, 80L, 2)
            } else if (op == "update") {
                carts.update(7, 80, 2)
            } else {
                carts.get(7)
            }
        }

        assertEquals(2, item.quantity)
        assertEquals("rollback:" + (if (op == "get") "read" else "write"), events.last())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun given__nonpositive_patch__when__updating__then__remove_existing_item_without_sku_lookup(quantity: Int) {
        existing(1)

        carts.update(7, 80, quantity)

        assertTrue(stored!!.items.isEmpty())
        verifyNoInteractions(em)
    }

    @Test
    fun given__existing_item__when__setting_positive_quantity__then__replace_quantity_without_stock_reservation() {
        val item = existing(1)
        sku.updatePrice(1L)

        carts.update(7, 80, Int.MAX_VALUE)

        assertEquals(Int.MAX_VALUE, item.quantity)
        assertEquals(5, sku.stockQuantity)
    }

    @Test
    fun given__missing_item__when__positive_patch__then__keep_original_bad_input() {
        assertThrows(IllegalArgumentException::class.java) { carts.update(7, 80, 1) }

        assertNull(stored)
        verifyNoInteractions(em)
    }

    @ParameterizedTest
    @ValueSource(strings = ["remove", "clear", "patch-delete"])
    fun given__missing_cart__when__removing__then__successful_noop_without_creating_parent(op: String) {
        if (op == "remove") {
            carts.remove(7, 80)
        } else if (op == "clear") {
            carts.clear(7)
        } else {
            carts.update(7, 80, 0)
        }

        verify(repository, never()).save(any(Cart::class.java))
        verifyNoInteractions(em)
        assertNull(stored)
    }

    @Test
    fun given__two_items__when__removing_then_clearing__then__mutate_only_current_cart_children() {
        existing(1)
        stored!!.addItem(CartItem.builder().skuId(81L).quantity(2).build())

        carts.remove(7, 80)
        assertEquals(81L, stored!!.items.first().skuId)
        carts.clear(7)

        assertTrue(stored!!.items.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "commit"])
    fun given__persistence_or_commit_failure__when__adding__then__never_return_success(stage: String) {
        failure = stage
        val expected: Class<out RuntimeException> = if (stage == "query") {
            DataAccessResourceFailureException::class.java
        } else {
            TransactionSystemException::class.java
        }

        assertThrows(expected) { carts.add(7, 80L, 1) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["get", "add"])
    fun given__no_actual_transaction__when__accessing__then__fail_before_actor(op: String) {
        val no = TransactionOperations.withoutTransaction()
        val service = JpaCarts(em, repository, no, no, actors)

        assertThrows(IllegalStateException::class.java) { operation(service, op) }

        verifyNoInteractions(actors, em, repository)
    }

    @ParameterizedTest
    @ValueSource(strings = ["get", "add"])
    fun given__wrong_read_mode__when__accessing__then__fail_before_actor(op: String) {
        if (op == "get") {
            read.isReadOnly = false
        } else {
            write.isReadOnly = true
        }

        assertThrows(IllegalStateException::class.java) { operation(carts, op) }

        verifyNoInteractions(actors, em, repository)
    }

    inner class Manager(val readMode: Boolean) : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(tx: Any, definition: TransactionDefinition) {
            events.add("begin:" + (if (readMode) "read" else "write"))
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit:" + (if (readMode) "read" else "write"))
            if (failure == "commit") {
                throw TransactionSystemException("synthetic commit failure")
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback:" + (if (readMode) "read" else "write"))
        }
    }

    companion object {
        fun problem(): DataAccessResourceFailureException {
            return DataAccessResourceFailureException("synthetic persistence failure")
        }

        private fun operation(carts: JpaCarts, op: String) {
            when (op) {
                "get" -> carts.get(7)
                "add" -> carts.add(7, 80L, 1)
                "update" -> carts.update(7, 80, 1)
                "remove" -> carts.remove(7, 80)
                else -> carts.clear(7)
            }
        }

        @JvmStatic
        fun rejectedActors(): Stream<Arguments> {
            return Stream.of("null", "wrong", "inactive", "deleted").flatMap { state ->
                Stream.of("get", "add", "update", "remove", "clear").map { op -> Arguments.of(state, op) }
            }
        }

        @JvmStatic
        fun badAdds(): Stream<Arguments> = Stream.of(
            Arguments.of(null, 1),
            Arguments.of(0L, 1),
            Arguments.of(80L, null),
            Arguments.of(80L, 0),
            Arguments.of(80L, -1),
        )
    }
}
