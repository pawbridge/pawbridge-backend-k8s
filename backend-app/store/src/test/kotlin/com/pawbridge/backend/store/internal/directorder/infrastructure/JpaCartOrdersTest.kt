package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository.CartRepository
import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import jakarta.persistence.PersistenceException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.util.ReflectionTestUtils
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
        carts = mockk<CartRepository>()
        cart = spyk(Cart.builder().userId(7L).build())
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
    fun `장바구니에 판매 중인 SKU 두 개가 있을 때__주문하면__한 트랜잭션에서 원래 순서와 재고 후속 작업을 보존하고 장바구니를 비운다`() {
        // given
        item(80, 2)
        item(81, 3)

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }
        every { f.products.findByIdWithLock(any()) } answers {
            f.writer()
            f.events.add("product-lock")
            Optional.of(f.product)
        }
        every { f.skus.findAllByProductIdWithLock(any()) } answers {
            f.writer()
            f.events.add("all-sku-lock")
            listOf(f.target, f.sibling)
        }
        every { f.jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
            f.writer()
            f.events.add("search")
            if (f.failAt == "search") {
                throw DirectOrderFixture.failure()
            }
            f.searchParameters.add(HashMap<String, Any?>(secondArg<Map<String, Any?>>()))
            1
        }
        every { f.skus.findAllById(any()) } answers {
            f.writer()
            f.events.add("sku-read-all")
            if (f.failAt == "read") {
                throw DirectOrderFixture.failure()
            }
            val ids = firstArg<Collection<Long?>>()
            listOf(f.target, f.sibling).filter { sku -> ids.contains(sku.id) }
        }
        every { f.writerOrders.save(any<Order>()) } answers {
            f.writer()
            f.events.add("order-save")
            if (f.failAt == "order") {
                throw DirectOrderFixture.failure()
            }
            val order = firstArg<Order>()
            DirectOrderFixture.id(order, f.identity++)
            ReflectionTestUtils.setField(order, "createdAt", f.time)
            f.orders[order.id!!] = order
            order
        }
        every { cart.clearItems() } answers {
            f.writer()
            f.events.add("cart-clear")
            callOriginal()
        }

        // when
        val result = service.create(7, command)

        // then
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
        verify(exactly = 1) { f.actors.currentForWrite(7) }
        verify(exactly = 1) { carts.findByUserId(7L) }
        verify(exactly = 1) { f.products.findByIdWithLock(90L) }
        verify(exactly = 1) { f.skus.findAllByProductIdWithLock(90L) }
        verify(exactly = 0) { f.skus.findById(any()) }
        verify(exactly = 0) { f.actors.currentForRead(any()) }
        confirmVerified(f.readerOrders)
        val saved = f.orders[result.orderId!!]!!
        for (row in saved.orderItems) {
            assertSame(saved, row.order)
        }
    }

    @Test
    fun `상품 잠금을 얻기 전에 가격이 바뀌었을 때__주문하면__합계와 항목이 같은 잠금 후 현재 가격을 쓴다`() {
        // given
        item(80, 2)

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }
        every { f.products.findByIdWithLock(any()) } answers {
            f.writer()
            f.events.add("product-lock")
            f.target.updatePrice(200L)
            Optional.of(f.product)
        }
        every { f.skus.findAllByProductIdWithLock(any()) } answers {
            f.writer()
            f.events.add("all-sku-lock")
            listOf(f.target, f.sibling)
        }
        every { f.jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
            f.writer()
            f.events.add("search")
            if (f.failAt == "search") {
                throw DirectOrderFixture.failure()
            }
            f.searchParameters.add(HashMap<String, Any?>(secondArg<Map<String, Any?>>()))
            1
        }
        every { f.skus.findAllById(any()) } answers {
            f.writer()
            f.events.add("sku-read-all")
            if (f.failAt == "read") {
                throw DirectOrderFixture.failure()
            }
            val ids = firstArg<Collection<Long?>>()
            listOf(f.target, f.sibling).filter { sku -> ids.contains(sku.id) }
        }
        every { f.writerOrders.save(any<Order>()) } answers {
            f.writer()
            f.events.add("order-save")
            if (f.failAt == "order") {
                throw DirectOrderFixture.failure()
            }
            val order = firstArg<Order>()
            DirectOrderFixture.id(order, f.identity++)
            ReflectionTestUtils.setField(order, "createdAt", f.time)
            f.orders[order.id!!] = order
            order
        }
        every { cart.clearItems() } answers {
            f.writer()
            f.events.add("cart-clear")
            callOriginal()
        }

        // when
        val result = service.create(7, command)

        // then
        assertEquals(400L, result.totalAmount)
        assertEquals(200L, result.items!!.first().price)
        assertTrue(f.events.indexOf("all-sku-lock") < f.events.indexOf("sku-read-all"))
        verify(exactly = 1) { f.products.findByIdWithLock(90L) }
        verify(exactly = 0) { f.skus.findById(any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "wrong", "inactive", "deleted"])
    fun `현재 행위자가 자격이 없을 때__주문하면__장바구니나 저장소 행 전에 거부한다`(kind: String) {
        // given
        f.actor = when (kind) {
            "null" -> null
            "wrong" -> StoreOrderActors.Actor(8, true, false)
            "inactive" -> StoreOrderActors.Actor(7, false, false)
            else -> StoreOrderActors.Actor(7, true, true)
        }

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }

        // when
        val thrown = assertThrows(OrderActorRejectedException::class.java) { service.create(7, command) }

        // then
        assertNotNull(thrown)
        verify(exactly = 1) { f.actors.currentForWrite(7) }
        confirmVerified(carts, f.products, f.skus, f.writerOrders, f.jdbc)
        assertEquals("rollback:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "empty", "only-stale"])
    fun `보이는 장바구니 항목이 없을 때__주문하면__장바구니 소비나 재고 작업 없이 원본의 빈 장바구니 오류를 돌려준다`(
        kind: String,
    ) {
        // given
        if (kind == "only-stale") {
            item(999, 2)
        }

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }
        if (kind == "missing") {
            every { carts.findByUserId(any()) } returns Optional.empty()
        }

        // when
        val failure = assertThrows(IllegalArgumentException::class.java) { service.create(7, command) }

        // then
        assertEquals("Cart is empty", failure.message)
        verify(exactly = 0) { cart.clearItems() }
        verify(exactly = 1) { carts.findByUserId(7L) }
        confirmVerified(f.products, f.writerOrders, f.jdbc)
        if (kind == "only-stale") {
            assertEquals(1, cart.items.size)
        }
    }

    @Test
    fun `판매 중인 항목과 사라진 항목이 섞여 있을 때__주문하면__보이는 항목만 구매하고 원본처럼 장바구니 전체를 비운다`() {
        // given
        item(80, 1)
        item(999, 3)

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }
        every { f.products.findByIdWithLock(any()) } answers {
            f.writer()
            f.events.add("product-lock")
            Optional.of(f.product)
        }
        every { f.skus.findAllByProductIdWithLock(any()) } answers {
            f.writer()
            f.events.add("all-sku-lock")
            listOf(f.target, f.sibling)
        }
        every { f.jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
            f.writer()
            f.events.add("search")
            if (f.failAt == "search") {
                throw DirectOrderFixture.failure()
            }
            f.searchParameters.add(HashMap<String, Any?>(secondArg<Map<String, Any?>>()))
            1
        }
        every { f.skus.findAllById(any()) } answers {
            f.writer()
            f.events.add("sku-read-all")
            if (f.failAt == "read") {
                throw DirectOrderFixture.failure()
            }
            val ids = firstArg<Collection<Long?>>()
            listOf(f.target, f.sibling).filter { sku -> ids.contains(sku.id) }
        }
        every { f.writerOrders.save(any<Order>()) } answers {
            f.writer()
            f.events.add("order-save")
            if (f.failAt == "order") {
                throw DirectOrderFixture.failure()
            }
            val order = firstArg<Order>()
            DirectOrderFixture.id(order, f.identity++)
            ReflectionTestUtils.setField(order, "createdAt", f.time)
            f.orders[order.id!!] = order
            order
        }
        every { cart.clearItems() } answers {
            f.writer()
            f.events.add("cart-clear")
            callOriginal()
        }

        // when
        val result = service.create(7, command)

        // then
        assertEquals(100L, result.totalAmount)
        assertEquals(1, result.items!!.size)
        assertTrue(cart.items.isEmpty())
        assertEquals(4, f.target.stockQuantity)
    }

    @Test
    fun `장바구니에 같은 SKU가 중복될 때__주문하면__암묵적 병합이나 재고 변경 없이 닫힌 채로 실패한다`() {
        // given
        item(80, 1)
        item(80, 2)

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }

        // when
        val thrown = assertThrows(PersistenceException::class.java) { service.create(7, command) }

        // then
        assertNotNull(thrown)
        confirmVerified(f.skus, f.products, f.writerOrders)
        verify(exactly = 0) { cart.clearItems() }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `저장된 수량이 양수가 아닐 때__주문하면__원본 재고 검증을 재사용한다`(quantity: Int) {
        // given
        item(80, quantity)

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { service.create(7, command) }

        // then
        assertNotNull(thrown)
        confirmVerified(f.products, f.writerOrders, f.jdbc)
        verify(exactly = 0) { cart.clearItems() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["inactive", "stock", "missing-under-lock"])
    fun `원본 재고 거절 사유가 있을 때__주문하면__장바구니를 소비하지 않고 롤백한다`(kind: String) {
        // given
        item(80, if (kind == "stock") 6 else 1)
        if (kind == "inactive") {
            f.product.updateStatus(ProductStatus.HIDDEN)
        }
        val type: Class<out RuntimeException> = if (kind == "missing-under-lock") {
            IllegalArgumentException::class.java
        } else {
            IllegalStateException::class.java
        }

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }
        every { f.products.findByIdWithLock(any()) } answers {
            f.writer()
            f.events.add("product-lock")
            Optional.of(f.product)
        }
        every { f.skus.findAllByProductIdWithLock(any()) } answers {
            f.writer()
            f.events.add("all-sku-lock")
            listOf(f.target, f.sibling)
        }
        if (kind == "missing-under-lock") {
            every { f.skus.findAllByProductIdWithLock(any()) } returns listOf(f.sibling)
        }

        // when
        val thrown = assertThrows(type) { service.create(7, command) }

        // then
        assertNotNull(thrown)
        assertEquals(5, f.target.stockQuantity)
        verify(exactly = 0) { cart.clearItems() }
        if (kind == "missing-under-lock") {
            verify(exactly = 1) { f.skus.findAllByProductIdWithLock(90L) }
        }
        confirmVerified(f.writerOrders)
        assertEquals("rollback:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["multiply", "sum"])
    fun `주문 금액이 넘칠 때__주문하면__장바구니를 소비하지 않고 재고와 프로젝션의 롤백을 요청하는 잘못된 입력으로 처리한다`(
        kind: String,
    ) {
        // given
        f.target.updatePrice(Long.MAX_VALUE)
        item(80, if (kind == "multiply") 2 else 1)
        if (kind == "sum") {
            f.sibling.updatePrice(1L)
            item(81, 1)
        }

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }
        every { f.products.findByIdWithLock(any()) } answers {
            f.writer()
            f.events.add("product-lock")
            Optional.of(f.product)
        }
        every { f.skus.findAllByProductIdWithLock(any()) } answers {
            f.writer()
            f.events.add("all-sku-lock")
            listOf(f.target, f.sibling)
        }
        every { f.jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
            f.writer()
            f.events.add("search")
            if (f.failAt == "search") {
                throw DirectOrderFixture.failure()
            }
            f.searchParameters.add(HashMap<String, Any?>(secondArg<Map<String, Any?>>()))
            1
        }
        every { f.skus.findAllById(any()) } answers {
            f.writer()
            f.events.add("sku-read-all")
            if (f.failAt == "read") {
                throw DirectOrderFixture.failure()
            }
            val ids = firstArg<Collection<Long?>>()
            listOf(f.target, f.sibling).filter { sku -> ids.contains(sku.id) }
        }

        // when
        val failure = assertThrows(IllegalArgumentException::class.java) { service.create(7, command) }

        // then
        assertInstanceOf(ArithmeticException::class.java, failure.cause)
        assertEquals(1, f.searchParameters.size)
        confirmVerified(f.writerOrders)
        verify(exactly = 0) { cart.clearItems() }
        assertEquals("rollback:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["cart", "search", "read", "order", "commit"])
    fun `조회나 후속 작업이나 주문 저장이나 커밋이 실패할 때__주문하면__성공을 돌려주지 않는다`(stage: String) {
        // given
        item(80, 1)
        f.failAt = stage
        val type: Class<out RuntimeException> = if (stage == "commit") {
            TransactionSystemException::class.java
        } else {
            DataAccessResourceFailureException::class.java
        }

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }
        every { f.products.findByIdWithLock(any()) } answers {
            f.writer()
            f.events.add("product-lock")
            Optional.of(f.product)
        }
        every { f.skus.findAllByProductIdWithLock(any()) } answers {
            f.writer()
            f.events.add("all-sku-lock")
            listOf(f.target, f.sibling)
        }
        every { f.jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
            f.writer()
            f.events.add("search")
            if (f.failAt == "search") {
                throw DirectOrderFixture.failure()
            }
            f.searchParameters.add(HashMap<String, Any?>(secondArg<Map<String, Any?>>()))
            1
        }
        every { f.skus.findAllById(any()) } answers {
            f.writer()
            f.events.add("sku-read-all")
            if (f.failAt == "read") {
                throw DirectOrderFixture.failure()
            }
            val ids = firstArg<Collection<Long?>>()
            listOf(f.target, f.sibling).filter { sku -> ids.contains(sku.id) }
        }
        every { f.writerOrders.save(any<Order>()) } answers {
            f.writer()
            f.events.add("order-save")
            if (f.failAt == "order") {
                throw DirectOrderFixture.failure()
            }
            val order = firstArg<Order>()
            DirectOrderFixture.id(order, f.identity++)
            ReflectionTestUtils.setField(order, "createdAt", f.time)
            f.orders[order.id!!] = order
            order
        }
        every { cart.clearItems() } answers {
            f.writer()
            f.events.add("cart-clear")
            callOriginal()
        }

        // when
        val thrown = assertThrows(type) { service.create(7, command) }

        // then
        assertNotNull(thrown)
        if (stage != "commit") {
            verify(exactly = 0) { cart.clearItems() }
            assertEquals(1, cart.items.size)
            assertEquals("rollback:writer", f.events.last())
        }
        // Mock persistence observes rollback requests; Root owns physical DB restoration after clear/commit failure.
    }

    @Test
    fun `잠금 후 선택한 SKU가 사라졌을 때__주문을 구성하면__장바구니를 소비하지 않고 고정된 실패를 돌려준다`() {
        // given
        item(80, 1)

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }
        every { f.products.findByIdWithLock(any()) } answers {
            f.writer()
            f.events.add("product-lock")
            Optional.of(f.product)
        }
        every { f.skus.findAllByProductIdWithLock(any()) } answers {
            f.writer()
            f.events.add("all-sku-lock")
            listOf(f.target, f.sibling)
        }
        every { f.jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
            f.writer()
            f.events.add("search")
            if (f.failAt == "search") {
                throw DirectOrderFixture.failure()
            }
            f.searchParameters.add(HashMap<String, Any?>(secondArg<Map<String, Any?>>()))
            1
        }
        every { f.skus.findAllById(any()) } returns listOf()

        // when
        val failure = assertThrows(PersistenceException::class.java) { service.create(7, command) }

        // then
        assertEquals("Checkout items cannot be read", failure.message)
        verify(exactly = 0) { cart.clearItems() }
        confirmVerified(f.writerOrders)
    }

    @Test
    fun `이미 소비된 장바구니에 새 항목이 없을 때__다시 주문하면__빈 장바구니 오류를 내고 주문은 하나만 남는다`() {
        // given
        item(80, 1)

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { carts.findByUserId(any()) } answers {
            f.writer()
            f.events.add("cart")
            if (f.failAt == "cart") {
                throw DirectOrderFixture.failure()
            }
            Optional.of(cart)
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            val ids = firstArg<Collection<Long?>>()
            listOf(80L, 81L).filter { ids.contains(it) }.map { id -> SkuReference(id, 90L) }
        }
        every { f.products.findByIdWithLock(any()) } answers {
            f.writer()
            f.events.add("product-lock")
            Optional.of(f.product)
        }
        every { f.skus.findAllByProductIdWithLock(any()) } answers {
            f.writer()
            f.events.add("all-sku-lock")
            listOf(f.target, f.sibling)
        }
        every { f.jdbc.update(any<String>(), any<Map<String, Any>>()) } answers {
            f.writer()
            f.events.add("search")
            if (f.failAt == "search") {
                throw DirectOrderFixture.failure()
            }
            f.searchParameters.add(HashMap<String, Any?>(secondArg<Map<String, Any?>>()))
            1
        }
        every { f.skus.findAllById(any()) } answers {
            f.writer()
            f.events.add("sku-read-all")
            if (f.failAt == "read") {
                throw DirectOrderFixture.failure()
            }
            val ids = firstArg<Collection<Long?>>()
            listOf(f.target, f.sibling).filter { sku -> ids.contains(sku.id) }
        }
        every { f.writerOrders.save(any<Order>()) } answers {
            f.writer()
            f.events.add("order-save")
            if (f.failAt == "order") {
                throw DirectOrderFixture.failure()
            }
            val order = firstArg<Order>()
            DirectOrderFixture.id(order, f.identity++)
            ReflectionTestUtils.setField(order, "createdAt", f.time)
            f.orders[order.id!!] = order
            order
        }
        every { cart.clearItems() } answers {
            f.writer()
            f.events.add("cart-clear")
            callOriginal()
        }

        service.create(7, command)

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { service.create(7, command) }

        // then
        assertNotNull(thrown)
        assertEquals(1, f.orders.size)
        assertEquals(4, f.target.stockQuantity)
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "read-only"])
    fun `작성자 트랜잭션이 잘못됐을 때__주문하면__행위자나 장바구니 전에 실패한다`(kind: String) {
        // given
        val tx: TransactionOperations = if (kind == "none") TransactionOperations.withoutTransaction() else f.read
        val service = JpaCartOrders(carts, f.writerOrders, f.skus, f.stocks, f.actors, tx)

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { service.create(7, command) }

        // then
        assertNotNull(thrown)
        confirmVerified(carts, f.actors, f.skus, f.products, f.writerOrders)
    }
}
