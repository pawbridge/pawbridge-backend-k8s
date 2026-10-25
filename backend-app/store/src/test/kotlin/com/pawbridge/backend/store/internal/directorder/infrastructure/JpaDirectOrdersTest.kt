package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.TestFaker
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.springframework.dao.DataAccessResourceFailureException
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
    fun `활성 회원과 잠긴 재고가 있을 때__구매 후 본인 주문을 조회하면__스냅샷과 전체 응답을 보존하고 후속 작업을 트랜잭션 안에서 처리한다`() {
        // given
        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            listOf(SkuReference(80L, 90L))
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
        every { f.skus.findById(any()) } answers {
            f.writer()
            f.events.add("sku-read")
            Optional.of(f.target)
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
        every { f.actors.currentForRead(any()) } answers {
            f.reader()
            f.events.add("actor:read")
            f.actor
        }
        every { f.readerOrders.findById(any()) } answers {
            f.reader()
            f.events.add("order-read")
            Optional.ofNullable(f.orders[firstArg<Long>()])
        }

        // when
        val created = f.service.create(7, f.command(2))

        // then
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
        verify(exactly = 1) { f.actors.currentForWrite(7) }
        verify(exactly = 1) { f.actors.currentForRead(7) }
        verify(exactly = 1) { f.products.findByIdWithLock(90L) }
        verify(exactly = 1) { f.skus.findAllByProductIdWithLock(90L) }
        verify(exactly = 1) { f.skus.findById(80L) }
        verify(exactly = 1) { f.readerOrders.findById(33L) }
    }

    @ParameterizedTest
    @MethodSource("actors")
    fun `현재 행위자가 자격이 없을 때__주문 흐름에 접근하면__주문 행이나 후속 작업 전에 거부한다`(
        state: String,
        operation: String,
    ) {
        // given
        f.actor = when (state) {
            "null" -> null
            "mismatch" -> StoreOrderActors.Actor(8, true, false)
            "inactive" -> StoreOrderActors.Actor(7, false, false)
            else -> StoreOrderActors.Actor(7, true, true)
        }

        if (operation == "create") {
            every { f.actors.currentForWrite(any()) } answers {
                f.writer()
                f.events.add("actor:write")
                f.actor
            }
        } else {
            every { f.actors.currentForRead(any()) } answers {
                f.reader()
                f.events.add("actor:read")
                f.actor
            }
        }

        // when
        val thrown = assertThrows(OrderActorRejectedException::class.java) {
            if (operation == "create") {
                f.service.create(7, f.command(1))
            } else {
                f.service.get(7, 33)
            }
        }

        // then
        assertNotNull(thrown)
        if (operation == "create") {
            verify(exactly = 1) { f.actors.currentForWrite(7) }
        } else {
            verify(exactly = 1) { f.actors.currentForRead(7) }
        }
        confirmVerified(f.actors, f.products, f.skus, f.writerOrders, f.readerOrders, f.jdbc)
        assertEquals("rollback:" + (if (operation == "create") "writer" else "reader"), f.events.last())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `수량이 양수가 아닐 때__구매하면__재고 조회 전에 거부하고 롤백한다`(quantity: Int) {
        // given
        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { f.service.create(7, f.command(quantity)) }

        // then
        assertNotNull(thrown)
        confirmVerified(f.products, f.skus, f.writerOrders, f.jdbc)
        assertEquals("rollback:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-sku", "missing-product", "hidden", "sold-out", "deleted", "insufficient-stock"])
    fun `원본 재고 거절 사유가 있을 때__구매하면__주문이나 스냅샷 없이 실패를 보존한다`(kind: String) {
        // given
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

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            listOf(SkuReference(80L, 90L))
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
        if (kind == "missing-sku") {
            every { f.skus.findProductIdsBySkuIdIn(any()) } returns listOf()
        }
        if (kind == "missing-product") {
            every { f.products.findByIdWithLock(any()) } returns Optional.empty()
        }

        // when
        val thrown = assertThrows(expected) { f.service.create(7, f.command(if (kind == "insufficient-stock") 6 else 1)) }

        // then
        assertNotNull(thrown)
        assertEquals(5, f.target.stockQuantity)
        confirmVerified(f.writerOrders, f.jdbc)
        assertEquals("rollback:writer", f.events.last())
    }

    @Test
    fun `Long 가격이 넘칠 때__구매하면__재고와 후속 작업의 롤백을 요청하고 주문은 저장하지 않는다`() {
        // given
        f.target.updatePrice(Long.MAX_VALUE)

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            listOf(SkuReference(80L, 90L))
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
        every { f.skus.findById(any()) } answers {
            f.writer()
            f.events.add("sku-read")
            Optional.of(f.target)
        }

        // when
        val failure = assertThrows(IllegalArgumentException::class.java) { f.service.create(7, f.command(2)) }

        // then
        assertInstanceOf(ArithmeticException::class.java, failure.cause)
        assertEquals("rollback:writer", f.events.last())
        assertEquals(1, f.searchParameters.size)
        confirmVerified(f.writerOrders)
        // This unit verifies the rollback request, not physical database restoration; Root owns the PG proof.
    }

    @ParameterizedTest
    @ValueSource(strings = ["search", "order"])
    fun `프로젝션이나 주문 저장이 실패할 때__구매하면__전파하고 작성자 롤백을 요청한다`(stage: String) {
        // given
        f.failAt = stage

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            listOf(SkuReference(80L, 90L))
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
        every { f.skus.findById(any()) } answers {
            f.writer()
            f.events.add("sku-read")
            Optional.of(f.target)
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

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { f.service.create(7, f.command(1)) }

        // then
        assertNotNull(thrown)
        assertEquals("rollback:writer", f.events.last())
        assertFalse(f.events.contains("commit:writer"))
        if (stage != "order") {
            confirmVerified(f.writerOrders)
        }
    }

    @Test
    fun `커밋이 실패할 때__생성하면__생성 성공을 돌려주지 않고 전파한다`() {
        // given
        f.failAt = "commit"

        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            listOf(SkuReference(80L, 90L))
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
        every { f.skus.findById(any()) } answers {
            f.writer()
            f.events.add("sku-read")
            Optional.of(f.target)
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

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { f.service.create(7, f.command(1)) }

        // then
        assertNotNull(thrown)
        assertEquals("commit:writer", f.events.last())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `다른 소유자의 주문이거나 없는 주문일 때__조회하면__작성 작업 없이 원본의 잘못된 입력 오류를 돌려준다`(missing: Boolean) {
        // given
        if (!missing) {
            val order = Order.builder().userId(8L).orderUuid(TestFaker.uuid().toString()).totalAmount(1L).build()
            DirectOrderFixture.id(order, 33)
            f.orders[33L] = order
        }

        every { f.actors.currentForRead(any()) } answers {
            f.reader()
            f.events.add("actor:read")
            f.actor
        }
        every { f.readerOrders.findById(any()) } answers {
            f.reader()
            f.events.add("order-read")
            Optional.ofNullable(f.orders[firstArg<Long>()])
        }

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { f.service.get(7, 33) }

        // then
        assertNotNull(thrown)
        confirmVerified(f.writerOrders, f.products, f.skus, f.jdbc)
        assertEquals("rollback:reader", f.events.last())
    }

    @Test
    fun `같은 직접 구매를 반복할 때__두 번 생성하면__멱등 키 없이 원본의 새 주문 의미를 유지한다`() {
        // given
        every { f.actors.currentForWrite(any()) } answers {
            f.writer()
            f.events.add("actor:write")
            f.actor
        }
        every { f.skus.findProductIdsBySkuIdIn(any()) } answers {
            f.writer()
            f.events.add("reference")
            listOf(SkuReference(80L, 90L))
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
        every { f.skus.findById(any()) } answers {
            f.writer()
            f.events.add("sku-read")
            Optional.of(f.target)
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

        // when
        val first = f.service.create(7, f.command(1))
        val second = f.service.create(7, f.command(1))

        // then
        assertNotEquals(first.orderId, second.orderId)
        assertNotEquals(first.orderUuid, second.orderUuid)
        assertEquals(3, f.target.stockQuantity)
        assertEquals(2, f.orders.size)
        assertEquals(2, f.searchParameters.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["write-in-ro", "read-in-rw"])
    fun `트랜잭션 모드가 잘못 연결됐을 때__접근하면__행위자나 저장소 행 전에 닫힌 채로 실패한다`(kind: String) {
        // given
        if (kind == "write-in-ro") {
            f.write.isReadOnly = true
        } else {
            f.read.isReadOnly = false
        }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) {
            if (kind == "write-in-ro") {
                f.service.create(7, f.command(1))
            } else {
                f.service.get(7, 33)
            }
        }

        // then
        assertNotNull(thrown)
        confirmVerified(f.actors, f.products, f.skus, f.writerOrders, f.readerOrders, f.jdbc)
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "get"])
    fun `실제 트랜잭션이 없을 때__접근하면__행위자나 저장소 행 전에 거부한다`(operation: String) {
        // given
        val absent = TransactionOperations.withoutTransaction()
        val service = JpaDirectOrders(f.writerOrders, f.readerOrders, f.skus, f.stocks, f.actors, absent, absent)

        // when
        val thrown = assertThrows(IllegalStateException::class.java) {
            if (operation == "create") {
                service.create(7, f.command(1))
            } else {
                service.get(7, 33)
            }
        }

        // then
        assertNotNull(thrown)
        confirmVerified(f.actors, f.products, f.skus, f.writerOrders, f.readerOrders, f.jdbc)
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(OrderStatus::class)
    fun `선택적 상태와 본인 스냅샷이 있을 때__목록 조회 후 상세를 조회하면__소유자 쿼리와 읽기 전용 행위자와 동일한 스냅샷을 재사용한다`(
        status: OrderStatus?,
    ) {
        // given
        val order = historyOrder(status ?: OrderStatus.PENDING)
        val query = StoreMemberReads.Request(1, 10, listOf())

        every { f.actors.currentForRead(any()) } answers {
            f.reader()
            f.events.add("actor:read")
            f.actor
        }
        if (status == null) {
            every { f.readerOrders.findByUserIdOrderByCreatedAtDesc(any(), any()) } answers {
                f.reader()
                f.events.add("history-page")
                val pageable = lastArg<Pageable>()
                assertEquals(1, pageable.pageNumber)
                assertEquals(10, pageable.pageSize)
                assertTrue(pageable.sort.isUnsorted)
                PageImpl(listOf(order), pageable, 11)
            }
        } else {
            every { f.readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(any(), any(), any()) } answers {
                f.reader()
                f.events.add("history-page")
                val pageable = lastArg<Pageable>()
                assertEquals(1, pageable.pageNumber)
                assertEquals(10, pageable.pageSize)
                assertTrue(pageable.sort.isUnsorted)
                PageImpl(listOf(order), pageable, 11)
            }
        }
        every { f.readerOrders.findWithItemsByUserIdAndIdIn(any(), any()) } answers {
            f.reader()
            f.events.add("history-items")
            listOf(order)
        }
        every { f.readerOrders.findById(any()) } answers {
            f.reader()
            f.events.add("order-read")
            Optional.ofNullable(f.orders[firstArg<Long>()])
        }

        // when
        val result = f.service.list(7, status?.name, query)

        // then
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
            verify(exactly = 1) { f.readerOrders.findByUserIdOrderByCreatedAtDesc(7L, any()) }
            verify(exactly = 0) { f.readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(any(), any(), any()) }
        } else {
            verify(exactly = 1) { f.readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(7L, status, any()) }
            verify(exactly = 0) { f.readerOrders.findByUserIdOrderByCreatedAtDesc(any(), any()) }
        }
        verify(exactly = 1) { f.readerOrders.findWithItemsByUserIdAndIdIn(7L, listOf(77L)) }
        verify(exactly = 1) { f.readerOrders.findById(77L) }
        verify(exactly = 2) { f.actors.currentForRead(7) }
        verify(exactly = 0) { f.actors.currentForWrite(any()) }
        confirmVerified(f.writerOrders, f.skus, f.products, f.jdbc)
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "mismatch", "inactive", "deleted"])
    fun `현재 회원이 없거나 자격이 없을 때__목록을 조회하면__주문 쿼리 전에 거부한다`(state: String) {
        // given
        f.actor = when (state) {
            "null" -> null
            "mismatch" -> StoreOrderActors.Actor(8, true, false)
            "inactive" -> StoreOrderActors.Actor(7, false, false)
            else -> StoreOrderActors.Actor(7, true, true)
        }

        every { f.actors.currentForRead(any()) } answers {
            f.reader()
            f.events.add("actor:read")
            f.actor
        }

        // when
        val thrown = assertThrows(OrderActorRejectedException::class.java) {
            f.service.list(7, null, StoreMemberReads.Request(0, 10, listOf()))
        }

        // then
        assertNotNull(thrown)
        verify(exactly = 1) { f.actors.currentForRead(7) }
        confirmVerified(f.actors, f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
        assertEquals(listOf("begin:reader", "actor:read", "rollback:reader"), f.events)
    }

    @ParameterizedTest
    @MethodSource("invalidHistoryPages")
    fun `과대하거나 재귀적이거나 오프셋이 큰 쿼리일 때__목록을 조회하면__트랜잭션이나 행위자 전에 거부한다`(
        request: StoreMemberReads.Request,
    ) {
        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) { f.service.list(7, null, request) }

        // then
        assertNotNull(thrown)
        assertTrue(f.events.isEmpty())
        confirmVerified(f.actors, f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @Test
    fun `유효한 단일 필드 정렬일 때__빈 페이지를 조회하면__관계 경로 없이 정렬 순서와 방향과 대소문자 무시를 보존한다`() {
        // given
        val sort = listOf(
            StoreMemberReads.Order("totalAmount", true, false),
            StoreMemberReads.Order("receiverName", false, true),
        )

        every { f.actors.currentForRead(any()) } answers {
            f.reader()
            f.events.add("actor:read")
            f.actor
        }
        every { f.readerOrders.findByUserIdOrderByCreatedAtDesc(any(), any()) } answers {
            val page = secondArg<Pageable>()
            assertEquals("totalAmount", page.sort.toList().first().property)
            assertTrue(page.sort.getOrderFor("totalAmount")!!.isAscending)
            assertTrue(page.sort.getOrderFor("receiverName")!!.isIgnoreCase)
            PageImpl(listOf<Order>(), page, 0)
        }

        // when
        val result = f.service.list(7, null, StoreMemberReads.Request(0, 100, sort))

        // then
        assertTrue(result.content.isEmpty())
        assertEquals(0L, result.totalElements)
        assertEquals(100, result.size)
        assertEquals(sort, result.sort)
        verify(exactly = 1) { f.readerOrders.findByUserIdOrderByCreatedAtDesc(7L, any()) }
        verify(exactly = 0) { f.readerOrders.findWithItemsByUserIdAndIdIn(any(), any()) }
        confirmVerified(f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @ParameterizedTest
    @ValueSource(strings = ["no-tx", "read-in-rw"])
    fun `이력 읽기 트랜잭션이 잘못 연결됐을 때__목록을 조회하면__행위자나 저장소 행 전에 실패한다`(kind: String) {
        // given
        val reads: TransactionOperations = if (kind == "no-tx") TransactionOperations.withoutTransaction() else f.write
        val service = JpaDirectOrders(f.writerOrders, f.readerOrders, f.skus, f.stocks, f.actors, f.write, reads)

        // when
        val thrown = assertThrows(IllegalStateException::class.java) {
            service.list(7, null, StoreMemberReads.Request(0, 10, listOf()))
        }

        // then
        assertNotNull(thrown)
        confirmVerified(f.actors, f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @Test
    fun `이력 쿼리가 실패할 때__목록을 조회하면__작성 없이 전파하고 읽기 트랜잭션을 롤백한다`() {
        // given
        every { f.actors.currentForRead(any()) } answers {
            f.reader()
            f.events.add("actor:read")
            f.actor
        }
        every { f.readerOrders.findByUserIdOrderByCreatedAtDesc(any(), any()) } answers {
            f.reader()
            f.events.add("history-page")
            throw DataAccessResourceFailureException("private history query")
        }

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) {
            f.service.list(7, null, StoreMemberReads.Request(0, 10, listOf()))
        }

        // then
        assertNotNull(thrown)
        assertEquals(listOf("begin:reader", "actor:read", "history-page", "rollback:reader"), f.events)
        verify(exactly = 1) { f.readerOrders.findByUserIdOrderByCreatedAtDesc(7L, any()) }
        confirmVerified(f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @Test
    fun `부모 페이지가 비어 있지 않고 대량 조회 순서가 다를 때__목록을 조회하면__검증된 소유자의 페이지 ID만 한 번 조회하고 부모 순서와 메타데이터를 유지한다`() {
        // given
        val first = historyOrder(77, OrderStatus.PAID)
        val second = historyOrder(88, OrderStatus.PAID)
        val sort = listOf(StoreMemberReads.Order("totalAmount", true, false))

        every { f.actors.currentForRead(any()) } answers {
            f.reader()
            f.events.add("actor:read")
            f.actor
        }
        every { f.readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(any(), any(), any()) } answers {
            f.reader()
            f.events.add("history-page")
            PageImpl(listOf(second, first), thirdArg<Pageable>(), 6)
        }
        every { f.readerOrders.findWithItemsByUserIdAndIdIn(any(), any()) } answers {
            f.reader()
            f.events.add("history-items")
            listOf(first, second)
        }

        // when
        val result = f.service.list(7, "PAID", StoreMemberReads.Request(1, 2, sort))

        // then
        assertEquals(listOf(88L, 77L), result.content.map { it.orderId })
        assertEquals(1, result.number)
        assertEquals(2, result.size)
        assertEquals(6L, result.totalElements)
        assertEquals(sort, result.sort)
        assertEquals(listOf("begin:reader", "actor:read", "history-page", "history-items", "commit:reader"), f.events)
        verify(exactly = 1) { f.readerOrders.findByUserIdAndStatusOrderByCreatedAtDesc(7L, OrderStatus.PAID, any()) }
        verify(exactly = 1) { f.readerOrders.findWithItemsByUserIdAndIdIn(7L, listOf(88L, 77L)) }
        verify(exactly = 0) { f.readerOrders.findByUserIdOrderByCreatedAtDesc(any(), any()) }
        confirmVerified(f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
    }

    @Test
    fun `페이지 항목 대량 조회가 실패할 때__목록을 조회하면__작성 없이 전파하고 같은 읽기 트랜잭션을 롤백한다`() {
        // given
        val order = historyOrder(OrderStatus.PENDING)

        every { f.actors.currentForRead(any()) } answers {
            f.reader()
            f.events.add("actor:read")
            f.actor
        }
        every { f.readerOrders.findByUserIdOrderByCreatedAtDesc(any(), any()) } answers {
            f.reader()
            f.events.add("history-page")
            PageImpl(listOf(order), secondArg<Pageable>(), 1)
        }
        every { f.readerOrders.findWithItemsByUserIdAndIdIn(any(), any()) } answers {
            f.reader()
            f.events.add("history-items")
            throw DataAccessResourceFailureException("private bulk query")
        }

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) {
            f.service.list(7, null, StoreMemberReads.Request(0, 10, listOf()))
        }

        // then
        assertNotNull(thrown)
        assertEquals(listOf("begin:reader", "actor:read", "history-page", "history-items", "rollback:reader"), f.events)
        verify(exactly = 1) { f.readerOrders.findWithItemsByUserIdAndIdIn(7L, listOf(77L)) }
        verify(exactly = 1) { f.readerOrders.findByUserIdOrderByCreatedAtDesc(7L, any()) }
        confirmVerified(f.readerOrders, f.writerOrders, f.products, f.skus, f.jdbc)
    }

    private fun historyOrder(status: OrderStatus): Order {
        return historyOrder(77, status)
    }

    private fun historyOrder(orderId: Long, status: OrderStatus): Order {
        val order = Order.builder()
            .userId(7L)
            .orderUuid(TestFaker.uuid().toString())
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
