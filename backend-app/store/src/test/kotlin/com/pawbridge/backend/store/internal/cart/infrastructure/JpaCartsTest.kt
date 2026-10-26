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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    val em: EntityManager = mockk<EntityManager>()
    val repository: CartRepository = mockk<CartRepository>()
    val actors: StoreOrderActors = mockk<StoreOrderActors>()
    val events = ArrayList<String>()
    val write = TransactionTemplate(Manager(false))
    val read = TransactionTemplate(Manager(true))
    val carts = JpaCarts(em, repository, write, read, actors)
    val product: Product = Product.builder().name("live product").imageUrl("unused image").status(ProductStatus.HIDDEN).build()
    val sku: ProductSKU = ProductSKU.builder().product(product).skuCode("LIVE-80").price(100L).stockQuantity(5).build()
    val query: TypedQuery<ProductSKU> = mockk<TypedQuery<ProductSKU>>()
    var stored: Cart? = null
    var actor: StoreOrderActors.Actor? = StoreOrderActors.Actor(7, true, false)
    var failure = ""

    @BeforeEach
    fun prepare() {
        write.setName("cart-write")
        read.setName("cart-read")
        read.isReadOnly = true
        ReflectionTestUtils.setField(sku, "id", 80L)
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
    fun `활성 회원일 때__두 번 담고 조회하면__행위자 확인을 먼저 직렬화하고 전송 형식과 담을 때마다의 재고 확인을 보존한다`() {
        // given
        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { actors.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        every { repository.save(any<Cart>()) } answers {
            active(false)
            events.add("save")
            val saved = firstArg<Cart>()
            stored = saved
            saved
        }

        every { em.find(ProductSKU::class.java, any()) } answers {
            active(false)
            events.add("sku")
            sku
        }

        every { em.createQuery(any<String>(), ProductSKU::class.java) } answers {
            active(true)
            events.add("sku-list")
            query
        }
        every { query.setParameter("ids", any<Any>()) } returns query
        every { query.resultList } answers {
            active(true)
            listOf(sku)
        }

        // when
        carts.add(7, 80L, 3)
        carts.add(7, 80L, 3)

        // then
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
        verify(exactly = 1) { query.setParameter("ids", setOf(80L)) }
        verify(exactly = 1) { repository.save(stored!!) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["no-parent", "empty-parent", "missing-sku"])
    fun `비었거나 낡은 장바구니일 때__조회하면__만들거나 지우지 않고 빈 배열을 돌려준다`(kind: String) {
        // given
        if (kind == "empty-parent") {
            stored = Cart.builder().userId(7L).build()
        }
        if (kind == "missing-sku") {
            existing(1)
        }

        every { actors.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        if (kind == "missing-sku") {
            every { em.createQuery(any<String>(), ProductSKU::class.java) } answers {
                active(true)
                events.add("sku-list")
                query
            }
            every { query.setParameter("ids", any<Any>()) } returns query
            every { query.resultList } answers {
                active(true)
                listOf<ProductSKU>()
            }
        }

        // when
        val items = carts.get(7)

        // then
        assertEquals(listOf<Carts.Item>(), items)
        verify(exactly = 0) { repository.save(any<Cart>()) }
        if (kind != "missing-sku") {
            confirmVerified(em, query)
        } else {
            assertEquals(1, stored!!.items.size)
        }
    }

    @ParameterizedTest
    @MethodSource("rejectedActors")
    fun `현재 행위자가 유효하지 않을 때__장바구니 연산에 접근하면__저장소 접근 전에 거부한다`(state: String, op: String) {
        // given
        actor = when (state) {
            "null" -> null
            "wrong" -> StoreOrderActors.Actor(8, true, false)
            "inactive" -> StoreOrderActors.Actor(7, false, false)
            else -> StoreOrderActors.Actor(7, true, true)
        }

        if (op == "get") {
            every { actors.currentForRead(any()) } answers {
                active(true)
                events.add("actor:read")
                actor
            }
        } else {
            every { actors.currentForWrite(any()) } answers {
                active(false)
                events.add("actor:write")
                actor
            }
        }

        // when
        // then
        assertThrows(CartActorRejectedException::class.java) { operation(carts, op) }

        confirmVerified(em, repository, query)
        if (op == "get") {
            verify(exactly = 1) { actors.currentForRead(7L) }
            verify(exactly = 0) { actors.currentForWrite(any()) }
        } else {
            verify(exactly = 1) { actors.currentForWrite(7L) }
            verify(exactly = 0) { actors.currentForRead(any()) }
        }
        assertEquals("rollback:" + (if (op == "get") "read" else "write"), events.last())
    }

    @ParameterizedTest
    @MethodSource("badAdds")
    fun `잘못된 담기 입력일 때__담으면__장바구니나 SKU 쓰기 없이 잘못된 입력 오류가 된다`(id: Long?, quantity: Int?) {
        // given
        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        // when
        // then
        assertThrows(IllegalArgumentException::class.java) { carts.add(7, id, quantity) }

        confirmVerified(repository, em)
        assertEquals("rollback:write", events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "stock"])
    fun `원래의 SKU 거부 조건일 때__담으면__장바구니를 만들기 전에 거부한다`(kind: String) {
        // given
        val found: ProductSKU? = if (kind == "missing") null else sku

        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { em.find(ProductSKU::class.java, any()) } answers {
            active(false)
            events.add("sku")
            found
        }

        // when
        // then
        assertThrows(IllegalArgumentException::class.java) { carts.add(7, 80L, if (kind == "stock") 6 else 1) }

        confirmVerified(repository)
        assertEquals("rollback:write", events.last())
    }

    @Test
    fun `기존 수량이 정수 최댓값일 때__담으면__항목을 바꾸지 않고 오버플로를 거부한다`() {
        // given
        val item = existing(Int.MAX_VALUE)

        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { em.find(ProductSKU::class.java, any()) } answers {
            active(false)
            events.add("sku")
            sku
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        // when
        // then
        assertThrows(IllegalArgumentException::class.java) { carts.add(7, 80L, 1) }

        assertEquals(Int.MAX_VALUE, item.quantity)
        assertEquals("rollback:write", events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["add", "update", "get"])
    fun `가격 곱셈이 Long 범위를 넘을 때__장바구니에 접근하면__트랜잭션을 롤백하고 거부한다`(op: String) {
        // given
        val item = existing(2)
        sku.updatePrice(Long.MAX_VALUE)

        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { actors.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        every { em.find(ProductSKU::class.java, any()) } answers {
            active(false)
            events.add("sku")
            sku
        }

        every { em.createQuery(any<String>(), ProductSKU::class.java) } answers {
            active(true)
            events.add("sku-list")
            query
        }
        every { query.setParameter("ids", any<Any>()) } returns query
        every { query.resultList } answers {
            active(true)
            listOf(sku)
        }

        // when
        // then
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
    fun `수량 패치가 0 이하일 때__수정하면__SKU 조회 없이 기존 항목을 제거한다`(quantity: Int) {
        // given
        existing(1)

        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        // when
        carts.update(7, 80, quantity)

        // then
        assertTrue(stored!!.items.isEmpty())
        confirmVerified(em)
    }

    @Test
    fun `기존 항목이 있을 때__양수 수량으로 수정하면__재고를 예약하지 않고 수량만 교체한다`() {
        // given
        val item = existing(1)
        sku.updatePrice(1L)

        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        every { em.find(ProductSKU::class.java, any()) } answers {
            active(false)
            events.add("sku")
            sku
        }

        // when
        carts.update(7, 80, Int.MAX_VALUE)

        // then
        assertEquals(Int.MAX_VALUE, item.quantity)
        assertEquals(5, sku.stockQuantity)
    }

    @Test
    fun `항목이 없을 때__양수로 패치하면__원래의 잘못된 입력 오류를 유지한다`() {
        // given
        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        // when
        // then
        assertThrows(IllegalArgumentException::class.java) { carts.update(7, 80, 1) }

        assertNull(stored)
        confirmVerified(em)
    }

    @ParameterizedTest
    @ValueSource(strings = ["remove", "clear", "patch-delete"])
    fun `장바구니가 없을 때__제거하면__부모를 만들지 않고 성공한 무동작이다`(op: String) {
        // given
        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        // when
        if (op == "remove") {
            carts.remove(7, 80)
        } else if (op == "clear") {
            carts.clear(7)
        } else {
            carts.update(7, 80, 0)
        }

        // then
        verify(exactly = 0) { repository.save(any<Cart>()) }
        confirmVerified(em)
        assertNull(stored)
    }

    @Test
    fun `항목이 둘일 때__제거하고 비우면__현재 장바구니의 자식만 변경한다`() {
        // given
        existing(1)
        stored!!.addItem(CartItem.builder().skuId(81L).quantity(2).build())

        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        // when
        carts.remove(7, 80)
        assertEquals(81L, stored!!.items.first().skuId)
        carts.clear(7)

        // then
        assertTrue(stored!!.items.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "commit"])
    fun `영속성이나 커밋 실패가 발생할 때__담으면__성공을 돌려주지 않는다`(stage: String) {
        // given
        failure = stage
        val expected: Class<out RuntimeException> = if (stage == "query") {
            DataAccessResourceFailureException::class.java
        } else {
            TransactionSystemException::class.java
        }

        every { actors.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }

        every { em.find(ProductSKU::class.java, any()) } answers {
            active(false)
            events.add("sku")
            sku
        }

        every { repository.findByUserId(any()) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("cart")
            if (failure == "query") {
                throw problem()
            }
            Optional.ofNullable(stored)
        }

        every { repository.save(any<Cart>()) } answers {
            active(false)
            events.add("save")
            val saved = firstArg<Cart>()
            stored = saved
            saved
        }

        // when
        // then
        assertThrows(expected) { carts.add(7, 80L, 1) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["get", "add"])
    fun `실제 트랜잭션이 없을 때__접근하면__행위자 확인 전에 실패한다`(op: String) {
        // given
        val no = TransactionOperations.withoutTransaction()
        val service = JpaCarts(em, repository, no, no, actors)

        // when
        // then
        assertThrows(IllegalStateException::class.java) { operation(service, op) }

        confirmVerified(actors, em, repository)
    }

    @ParameterizedTest
    @ValueSource(strings = ["get", "add"])
    fun `읽기 모드가 잘못됐을 때__접근하면__행위자 확인 전에 실패한다`(op: String) {
        // given
        if (op == "get") {
            read.isReadOnly = false
        } else {
            write.isReadOnly = true
        }

        // when
        // then
        assertThrows(IllegalStateException::class.java) { operation(carts, op) }

        confirmVerified(actors, em, repository)
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
