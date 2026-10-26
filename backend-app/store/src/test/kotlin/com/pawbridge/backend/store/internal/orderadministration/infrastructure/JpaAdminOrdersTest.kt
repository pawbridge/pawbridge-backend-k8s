package com.pawbridge.backend.store.internal.orderadministration.infrastructure

import com.pawbridge.backend.store.api.StoreOrderAdministrators
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.DeliveryStatus
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrderNotFoundException
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.application.AdministratorRejectedException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceException
import jakarta.persistence.TypedQuery
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.Optional
import java.util.stream.Stream

class JpaAdminOrdersTest {
    val em = mockk<EntityManager>()
    val orders = mockk<OrderRepository>()
    val itemFetch = mockk<TypedQuery<Order>>()
    val administrators = mockk<StoreOrderAdministrators>()
    val events = ArrayList<String>()
    val writes = TransactionTemplate(Manager("write"))
    val reads = TransactionTemplate(Manager("read")).apply { isReadOnly = true }
    val service = JpaAdminOrders(em, orders, writes, reads, administrators)
    val query = AdminOrders.Query(null, null, null, null, "createdAt", "desc", 0, 20)
    var actor: StoreOrderAdministrators.Actor? = StoreOrderAdministrators.Actor(7, true, false, "ROLE_ADMIN")
    var failure = ""

    @AfterEach
    fun close() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    fun storedOrder(): Order {
        val entity = Order.builder().userId(88L).orderUuid("stored-uuid").totalAmount(9007199254740993L)
            .receiverName("stored receiver").receiverPhone("stored phone").deliveryAddress("stored address")
            .deliveryMessage("stored message").build()
        ReflectionTestUtils.setField(entity, "id", 33L)
        ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.of(2026, 10, 21, 2, 0, 1, 123456789))
        entity.orderItems.add(
            OrderItem.builder().order(entity).productName("stored product").skuCode("STORED-SKU")
                .price(9007199254740993L).quantity(1).build(),
        )
        return entity
    }

    fun active(readOnly: Boolean) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertEquals(readOnly, TransactionSynchronizationManager.isCurrentTransactionReadOnly())
    }

    fun access(op: String) {
        when (op) {
            "list" -> service.list(7, query)
            "get" -> service.get(7, 33)
            "status" -> service.updateStatus(7, 33, "PAID")
            else -> service.updateDelivery(7, 33, "SHIPPING")
        }
    }

    @Test
    fun `현재 DB 기준 관리자일 때__다른 회원의 주문을 조회하면__읽기 전용 트랜잭션 안에서 전체 스냅샷을 그대로 돌려준다`() {
        // given
        val order = spyk(storedOrder())

        every { order.orderItems } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("map")
            callOriginal()
        }
        every { administrators.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }
        every { orders.findById(any()) } answers {
            active(true)
            events.add("get")
            Optional.of(order)
        }

        // when
        val result = service.get(7, 33)

        // then
        assertEquals(33L, result.orderId)
        assertEquals(88L, result.userId)
        assertEquals(9007199254740993L, result.totalAmount)
        assertEquals("stored-uuid", result.orderUuid)
        assertEquals("PENDING", result.status)
        assertEquals("READY", result.deliveryStatus)
        assertEquals("stored receiver", result.receiverName)
        assertEquals("stored phone", result.receiverPhone)
        assertEquals("stored address", result.deliveryAddress)
        assertEquals("stored message", result.deliveryMessage)
        assertEquals(9007199254740993L, result.items!!.first().price)
        assertEquals("stored product", result.items!!.first().productName)
        assertEquals(listOf("begin:read", "actor:read", "get", "map", "commit:read"), events)
        verify(exactly = 1) { administrators.currentForRead(7) }
        verify(exactly = 1) { orders.findById(33L) }
        verify(exactly = 0) { administrators.currentForWrite(any()) }
        confirmVerified(em)
    }

    @ParameterizedTest
    @ValueSource(strings = ["READY", "SHIPPING", "DELIVERED"])
    fun `관리자와 잠긴 주문이 있을 때__배송 상태를 변경하면__관리자 확인과 주문 잠금과 flush 순서로 배송 상태만 바꾼다`(
        delivery: String,
    ) {
        // given
        val order = spyk(storedOrder())

        every { order.orderItems } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("map")
            callOriginal()
        }
        every { administrators.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }
        every { em.find(any<Class<Order>>(), any<Any>(), any<LockModeType>()) } answers {
            active(false)
            events.add("order-lock")
            order
        }
        every { em.flush() } answers {
            active(false)
            events.add("flush")
            if (order.deliveryStatus == null || order.status == null || failure == "flush") {
                throw PersistenceException("synthetic constraint failure")
            }
        }

        // when
        val result = service.updateDelivery(7, 33, delivery)

        // then
        assertEquals(delivery, result.deliveryStatus)
        assertEquals("PENDING", result.status)
        assertEquals(9007199254740993L, result.totalAmount)
        assertEquals(88L, result.userId)
        assertEquals("stored-uuid", result.orderUuid)
        assertEquals("stored address", result.deliveryAddress)
        assertEquals(1, result.items!!.size)
        assertEquals(listOf("begin:write", "actor:write", "order-lock", "flush", "map", "commit:write"), events)
        verify(exactly = 1) { administrators.currentForWrite(7) }
        verify(exactly = 1) { em.find(Order::class.java, 33L, LockModeType.PESSIMISTIC_WRITE) }
        verify(exactly = 1) { em.flush() }
        confirmVerified(em)
        confirmVerified(orders)
        verify(exactly = 0) { administrators.currentForRead(any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PENDING", "PAID", "COMPLETED", "CANCELLED", "FAILED"])
    fun `관리자와 잠긴 주문이 있을 때__주문 상태를 변경하면__관리자 확인과 주문 잠금과 flush 순서로 주문 상태만 바꾼다`(
        status: String,
    ) {
        // given
        val order = spyk(storedOrder())

        every { order.orderItems } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("map")
            callOriginal()
        }
        every { administrators.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }
        every { em.find(any<Class<Order>>(), any<Any>(), any<LockModeType>()) } answers {
            active(false)
            events.add("order-lock")
            order
        }
        every { em.flush() } answers {
            active(false)
            events.add("flush")
            if (order.deliveryStatus == null || order.status == null || failure == "flush") {
                throw PersistenceException("synthetic constraint failure")
            }
        }

        // when
        val result = service.updateStatus(7, 33, status)

        // then
        assertEquals(status, result.status)
        assertEquals("READY", result.deliveryStatus)
        assertEquals(9007199254740993L, result.totalAmount)
        assertEquals(88L, result.userId)
        assertEquals("stored-uuid", result.orderUuid)
        assertEquals("stored address", result.deliveryAddress)
        assertEquals(1, result.items!!.size)
        assertEquals(listOf("begin:write", "actor:write", "order-lock", "flush", "map", "commit:write"), events)
        verify(exactly = 1) { administrators.currentForWrite(7) }
        verify(exactly = 1) { em.find(Order::class.java, 33L, LockModeType.PESSIMISTIC_WRITE) }
        verify(exactly = 1) { em.flush() }
        confirmVerified(em)
        confirmVerified(orders)
        verify(exactly = 0) { administrators.currentForRead(any()) }
    }

    @Test
    fun `주문 상태가 null일 때__변경하면__관리자 확인과 주문 잠금 뒤 갱신과 flush 없이 일반 실패를 던진다`() {
        // given
        val order = spyk(storedOrder())

        every { administrators.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }
        every { em.find(any<Class<Order>>(), any<Any>(), any<LockModeType>()) } answers {
            active(false)
            events.add("order-lock")
            order
        }

        // when
        val failure = assertThrows(IllegalStateException::class.java) { service.updateStatus(7, 33, null) }

        // then
        assertEquals("Order status cannot be stored", failure.message)
        assertEquals("PENDING", order.status!!.name)
        assertEquals(listOf("begin:write", "actor:write", "order-lock", "rollback:write"), events)
        verify(exactly = 0) { order.updateStatus(any()) }
        verify(exactly = 0) { em.flush() }
    }

    @ParameterizedTest
    @MethodSource("invalidActors")
    fun `자격이 없거나 정확히 일치하지 않는 현재 DB 관리자일 때__어느 작업이든 하면__주문 행에 닿기 전에 거부한다`(
        state: String,
        op: String,
    ) {
        // given
        actor = actorFor(state)

        if (op == "write" || op == "status") {
            every { administrators.currentForWrite(any()) } answers {
                active(false)
                events.add("actor:write")
                actor
            }
        } else {
            every { administrators.currentForRead(any()) } answers {
                active(true)
                events.add("actor:read")
                actor
            }
        }

        // when
        val thrown = assertThrows(AdministratorRejectedException::class.java) { access(op) }

        // then
        assertTrue(thrown is AdministratorRejectedException)
        confirmVerified(em, orders)
        assertEquals("rollback:" + (if (op == "write" || op == "status") "write" else "read"), events.last())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "id", "userId", "orderUuid", "totalAmount", "status", "deliveryAddress", "deliveryStatus",
            "receiverName", "receiverPhone", "deliveryMessage", "createdAt", "updatedAt",
        ],
    )
    fun `존재하는 루트 스칼라 정렬 속성일 때__목록을 조회하면__원래의 리포지터리 사양과 페이지 메타데이터를 읽기 트랜잭션 안에서 돌려준다`(
        property: String,
    ) {
        // given
        val order = spyk(storedOrder())
        val input = AdminOrders.Query(null, null, null, null, property, "ASC", 0, 20)

        every { order.orderItems } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("map")
            callOriginal()
        }
        every { administrators.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }
        every { orders.findAll(any<Specification<Order>>(), any<Pageable>()) } answers {
            active(true)
            events.add("list")
            PageImpl(listOf(order), arg<Pageable>(1), 1)
        }
        every { em.createQuery(any<String>(), any<Class<Order>>()) } returns itemFetch
        every { itemFetch.setParameter(any<String>(), any<Any>()) } returns itemFetch
        every { itemFetch.resultList } answers {
            active(true)
            events.add("batch")
            listOf(order)
        }

        // when
        val result = service.list(7, input)

        // then
        assertEquals(1, result.content!!.size)
        assertEquals(1L, result.totalElements)
        assertEquals(20, result.size)
        assertEquals(0, result.number)
        assertEquals(property, result.sortBy)
        assertTrue(result.ascending)
        verify(exactly = 1) {
            orders.findAll(
                any<Specification<Order>>(),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, property)),
            )
        }
        verify(exactly = 1) {
            em.createQuery("select distinct o from Order o left join fetch o.orderItems where o.id in :ids", Order::class.java)
        }
        verify(exactly = 1) { itemFetch.setParameter("ids", listOf(33L)) }
        verify(exactly = 1) { itemFetch.resultList }
        confirmVerified(em, itemFetch)
        assertEquals(listOf("begin:read", "actor:read", "list", "batch", "map", "commit:read"), events)
    }

    @Test
    fun `항목이 있는 페이지일 때__항목을 일괄 조회하면__원래의 순서와 메타데이터와 스냅샷을 유지한다`() {
        // given
        val order = spyk(storedOrder())
        val second = Order.builder().userId(99L).orderUuid("second-uuid").totalAmount(1400L)
            .receiverName("second receiver").receiverPhone("second phone").deliveryAddress("second address").build()
        ReflectionTestUtils.setField(second, "id", 22L)
        second.orderItems.add(
            OrderItem.builder().order(second).productName("second product").skuCode("SECOND-SKU").price(700L).quantity(2).build(),
        )
        val pageable = PageRequest.of(2, 2, Sort.by(Sort.Direction.DESC, "id"))

        every { order.orderItems } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("map")
            callOriginal()
        }
        every { administrators.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }
        every { orders.findAll(any<Specification<Order>>(), any<Pageable>()) } answers {
            active(true)
            events.add("list")
            PageImpl(listOf(order, second), pageable, 77)
        }
        every { em.createQuery(any<String>(), any<Class<Order>>()) } returns itemFetch
        every { itemFetch.setParameter(any<String>(), any<Any>()) } returns itemFetch
        every { itemFetch.resultList } answers {
            active(true)
            events.add("batch")
            listOf(second, order)
        }

        // when
        val result = service.list(7, AdminOrders.Query(null, null, null, null, "id", "desc", 2, 2))

        // then
        assertEquals(listOf(33L, 22L), result.content!!.map { item -> item.orderId })
        assertEquals(77L, result.totalElements)
        assertEquals(2, result.number)
        assertEquals(2, result.size)
        assertEquals("id", result.sortBy)
        assertFalse(result.ascending)
        assertEquals(9007199254740993L, result.content!!.first().items!!.first().price)
        assertEquals("second product", result.content!!.last().items!!.first().productName)
        assertEquals(2, result.content!!.last().items!!.first().quantity)
        verify(exactly = 1) { orders.findAll(any<Specification<Order>>(), pageable) }
        verify(exactly = 1) { itemFetch.setParameter("ids", listOf(33L, 22L)) }
        verify(exactly = 1) { itemFetch.resultList }
        verify(exactly = 1) {
            em.createQuery("select distinct o from Order o left join fetch o.orderItems where o.id in :ids", Order::class.java)
        }
        confirmVerified(em, itemFetch)
        assertEquals(listOf("begin:read", "actor:read", "list", "batch", "map", "commit:read"), events)
    }

    @Test
    fun `빈 페이지일 때__항목을 일괄 조회하면__조회를 건너뛰고 메타데이터를 유지한다`() {
        // given
        val pageable = PageRequest.of(4, 20, Sort.by(Sort.Direction.DESC, "id"))

        every { administrators.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }
        every { orders.findAll(any<Specification<Order>>(), any<Pageable>()) } answers {
            active(true)
            events.add("list")
            PageImpl<Order>(listOf(), pageable, 77)
        }

        // when
        val result = service.list(7, AdminOrders.Query(null, null, null, null, "id", "desc", 4, 20))

        // then
        assertTrue(result.content!!.isEmpty())
        assertEquals(77L, result.totalElements)
        assertEquals(4, result.number)
        assertEquals(20, result.size)
        verify(exactly = 1) { orders.findAll(any<Specification<Order>>(), pageable) }
        confirmVerified(em, itemFetch)
        assertEquals(listOf("begin:read", "actor:read", "list", "commit:read"), events)
    }

    @Test
    fun `항목 일괄 조회가 실패했을 때__목록을 조회하면__매핑이나 부분 응답 없이 롤백한다`() {
        // given
        val order = spyk(storedOrder())

        every { administrators.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }
        every { orders.findAll(any<Specification<Order>>(), any<Pageable>()) } answers {
            active(true)
            events.add("list")
            PageImpl(listOf(order), arg<Pageable>(1), 1)
        }
        every { em.createQuery(any<String>(), any<Class<Order>>()) } returns itemFetch
        every { itemFetch.setParameter(any<String>(), any<Any>()) } returns itemFetch
        every { itemFetch.resultList } answers {
            active(true)
            events.add("batch")
            throw problem()
        }

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { service.list(7, query) }

        // then
        assertTrue(thrown is DataAccessResourceFailureException)
        assertEquals(listOf("begin:read", "actor:read", "list", "batch", "rollback:read"), events)
        verify(exactly = 0) { order.orderItems }
    }

    @ParameterizedTest
    @MethodSource("badQueries")
    fun `지원하지 않는 정렬이거나 페이지 범위를 넘었을 때__목록을 조회하면__트랜잭션과 관리자 조회 전에 고정된 잘못된 입력 예외를 던진다`(
        input: AdminOrders.Query,
    ) {
        // when
        val failure = assertThrows(IllegalArgumentException::class.java) { service.list(7, input) }

        // then
        assertEquals("Admin order query is not supported", failure.message)
        assertTrue(events.isEmpty())
        confirmVerified(em, orders, administrators)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ASC", "desc", "anything"])
    fun `원래의 정렬 방향 문자열일 때__크기 상한으로 목록을 조회하면__ASC만 오름차순으로 해석한다`(direction: String) {
        // given
        val order = spyk(storedOrder())

        every { administrators.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }
        every { orders.findAll(any<Specification<Order>>(), any<Pageable>()) } answers {
            active(true)
            events.add("list")
            PageImpl(listOf(order), arg<Pageable>(1), 1)
        }
        every { em.createQuery(any<String>(), any<Class<Order>>()) } returns itemFetch
        every { itemFetch.setParameter(any<String>(), any<Any>()) } returns itemFetch
        every { itemFetch.resultList } answers {
            active(true)
            events.add("batch")
            listOf(order)
        }

        // when
        val result = service.list(7, AdminOrders.Query(null, null, null, null, "id", direction, 0, 100))

        // then
        assertEquals("ASC" == direction, result.ascending)
        assertEquals(100, result.size)
    }

    @Test
    fun `모든 필터와 와일드카드 키워드가 있을 때__사양을 만들면__원래의 필드와 이스케이프되지 않은 LIKE 패턴을 유지한다`() {
        // given
        val order = spyk(storedOrder())
        val root = mockk<Root<Order>>()
        val criteria = mockk<CriteriaQuery<*>>()
        val cb = mockk<CriteriaBuilder>()
        val predicate = mockk<Predicate>()
        val status = path<Any>()
        val delivery = path<Any>()
        val user = path<Any>()
        val uuid = path<String>()
        val receiver = path<String>()

        every { administrators.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }
        every { orders.findAll(any<Specification<Order>>(), any<Pageable>()) } answers {
            active(true)
            events.add("list")
            PageImpl(listOf(order), arg<Pageable>(1), 1)
        }
        every { em.createQuery(any<String>(), any<Class<Order>>()) } returns itemFetch
        every { itemFetch.setParameter(any<String>(), any<Any>()) } returns itemFetch
        every { itemFetch.resultList } answers {
            active(true)
            events.add("batch")
            listOf(order)
        }

        every { root.get<Any>("status") } returns status
        every { root.get<Any>("deliveryStatus") } returns delivery
        every { root.get<Any>("userId") } returns user
        every { root.get<String>("orderUuid") } returns uuid
        every { root.get<String>("receiverName") } returns receiver
        every { cb.equal(any<Expression<*>>(), any<Any>()) } returns predicate
        every { cb.lower(any<Expression<String>>()) } answers { firstArg<Expression<String>>() }
        every { cb.like(any<Expression<String>>(), any<String>()) } returns predicate
        every { cb.and(any<Expression<Boolean>>(), any<Expression<Boolean>>()) } returns predicate
        every { cb.or(*anyVararg<Predicate>()) } returns predicate

        // when
        service.list(7, AdminOrders.Query("PAID", "SHIPPING", 99L, " A%_ B ", "createdAt", "desc", 0, 20))

        // then
        val specification = slot<Specification<Order>>()
        verify(exactly = 1) { orders.findAll(capture(specification), any<Pageable>()) }
        specification.captured.toPredicate(root, criteria, cb)
        verify(exactly = 1) { cb.equal(status, OrderStatus.PAID) }
        verify(exactly = 1) { cb.equal(delivery, DeliveryStatus.SHIPPING) }
        verify(exactly = 1) { cb.equal(user, 99L) }
        verify(exactly = 1) { cb.like(uuid, "% a%_ b %") }
        verify(exactly = 1) { cb.like(receiver, "% a%_ b %") }
    }

    @Test
    fun `공백 키워드일 때__사양을 만들면__LIKE 조건을 추가하지 않는다`() {
        // given
        val order = spyk(storedOrder())
        val root = mockk<Root<Order>>()
        val criteria = mockk<CriteriaQuery<*>>()
        val cb = mockk<CriteriaBuilder>()

        every { administrators.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }
        every { orders.findAll(any<Specification<Order>>(), any<Pageable>()) } answers {
            active(true)
            events.add("list")
            PageImpl(listOf(order), arg<Pageable>(1), 1)
        }
        every { em.createQuery(any<String>(), any<Class<Order>>()) } returns itemFetch
        every { itemFetch.setParameter(any<String>(), any<Any>()) } returns itemFetch
        every { itemFetch.resultList } answers {
            active(true)
            events.add("batch")
            listOf(order)
        }

        // when
        service.list(7, AdminOrders.Query(null, null, null, "  ", "id", "desc", 0, 20))

        // then
        val specification = slot<Specification<Order>>()
        verify(exactly = 1) { orders.findAll(capture(specification), any<Pageable>()) }
        assertNull(specification.captured.toPredicate(root, criteria, cb))
        confirmVerified(root, cb)
    }

    @Test
    fun `없는 주문일 때__조회와 상태 변경을 하면__원래의 404와 400 계열을 구분해 던진다`() {
        // given
        every { administrators.currentForRead(any()) } answers {
            active(true)
            events.add("actor:read")
            actor
        }
        every { administrators.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }
        every { orders.findById(any()) } returns Optional.empty<Order>()
        every { em.find(any<Class<Order>>(), any<Any>(), any<LockModeType>()) } returns null

        // when
        val readFailure = assertThrows(AdminOrderNotFoundException::class.java) { service.get(7, 33) }
        val writeFailure = assertThrows(IllegalArgumentException::class.java) {
            service.updateDelivery(7, 33, "SHIPPING")
        }
        val statusFailure = assertThrows(IllegalArgumentException::class.java) { service.updateStatus(7, 33, "PAID") }

        // then
        assertTrue(readFailure is AdminOrderNotFoundException)
        assertTrue(writeFailure is IllegalArgumentException)
        assertTrue(statusFailure is IllegalArgumentException)
        assertEquals("rollback:write", events.last())
        verify(exactly = 0) { em.flush() }
    }

    @Test
    fun `배송 상태가 null일 때__변경하면__관리자 확인과 주문 잠금 뒤 갱신과 flush 없이 일반 실패를 던진다`() {
        // given
        val order = spyk(storedOrder())

        every { administrators.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }
        every { em.find(any<Class<Order>>(), any<Any>(), any<LockModeType>()) } answers {
            active(false)
            events.add("order-lock")
            order
        }

        // when
        val failure = assertThrows(IllegalStateException::class.java) { service.updateDelivery(7, 33, null) }

        // then
        assertEquals("Delivery status cannot be stored", failure.message)
        assertEquals(DeliveryStatus.READY, order.deliveryStatus)
        assertEquals(listOf("begin:write", "actor:write", "order-lock", "rollback:write"), events)
        verify(exactly = 0) { order.updateDeliveryStatus(any()) }
        verify(exactly = 0) { em.flush() }
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `조회나 커밋이 실패했을 때__주문을 관리하면__성공 없이 예외를 그대로 전파한다`(op: String, stage: String) {
        // given
        failure = stage
        val order = spyk(storedOrder())
        val type: Class<out RuntimeException> = if (stage == "commit") {
            TransactionSystemException::class.java
        } else {
            DataAccessResourceFailureException::class.java
        }

        if (op == "list") {
            every { administrators.currentForRead(any()) } answers {
                active(true)
                events.add("actor:read")
                actor
            }
            every { orders.findAll(any<Specification<Order>>(), any<Pageable>()) } answers {
                active(true)
                events.add("list")
                if (failure == "query") {
                    throw problem()
                }
                PageImpl(listOf(order), arg<Pageable>(1), 1)
            }
            every { em.createQuery(any<String>(), any<Class<Order>>()) } returns itemFetch
            every { itemFetch.setParameter(any<String>(), any<Any>()) } returns itemFetch
            every { itemFetch.resultList } answers {
                active(true)
                events.add("batch")
                listOf(order)
            }
        } else if (op == "get") {
            every { administrators.currentForRead(any()) } answers {
                active(true)
                events.add("actor:read")
                actor
            }
            every { orders.findById(any()) } answers {
                active(true)
                events.add("get")
                if (failure == "query") {
                    throw problem()
                }
                Optional.of(order)
            }
        } else {
            every { administrators.currentForWrite(any()) } answers {
                active(false)
                events.add("actor:write")
                actor
            }
            every { em.find(any<Class<Order>>(), any<Any>(), any<LockModeType>()) } answers {
                active(false)
                events.add("order-lock")
                if (failure == "query") {
                    throw problem()
                }
                order
            }
            every { em.flush() } answers {
                active(false)
                events.add("flush")
                if (order.deliveryStatus == null || order.status == null || failure == "flush") {
                    throw PersistenceException("synthetic constraint failure")
                }
            }
        }

        // when
        val thrown = assertThrows(type) { access(op) }

        // then
        assertTrue(type.isInstance(thrown))
    }

    @ParameterizedTest
    @ValueSource(strings = ["write", "status"])
    fun `flush가 실패했을 때__배송이나 주문 상태를 변경하면__예외를 전파하고 롤백을 요청한다`(op: String) {
        // given
        failure = "flush"
        val order = spyk(storedOrder())

        every { administrators.currentForWrite(any()) } answers {
            active(false)
            events.add("actor:write")
            actor
        }
        every { em.find(any<Class<Order>>(), any<Any>(), any<LockModeType>()) } answers {
            active(false)
            events.add("order-lock")
            order
        }
        every { em.flush() } answers {
            active(false)
            events.add("flush")
            if (order.deliveryStatus == null || order.status == null || failure == "flush") {
                throw PersistenceException("synthetic constraint failure")
            }
        }

        // when
        val thrown = assertThrows(PersistenceException::class.java) { access(op) }

        // then
        assertTrue(thrown is PersistenceException)
        assertEquals("rollback:write", events.last())
    }

    @ParameterizedTest
    @MethodSource("txModes")
    fun `트랜잭션이 없거나 잘못됐을 때__주문을 관리하면__관리자나 주문 조회 전에 실패한다`(
        op: String,
        kind: String,
    ) {
        // given
        var w: TransactionOperations = writes
        var r: TransactionOperations = reads
        if (kind == "none") {
            w = TransactionOperations.withoutTransaction()
            r = w
        } else {
            writes.isReadOnly = true
            reads.isReadOnly = false
        }
        val service = JpaAdminOrders(em, orders, w, r, administrators)

        // when
        val thrown = assertThrows(IllegalStateException::class.java) {
            when (op) {
                "list" -> service.list(7, query)
                "get" -> service.get(7, 33)
                "status" -> service.updateStatus(7, 33, "PAID")
                else -> service.updateDelivery(7, 33, "SHIPPING")
            }
        }

        // then
        assertTrue(thrown is IllegalStateException)
        confirmVerified(em, orders, administrators)
    }

    inner class Manager(val name: String) : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(tx: Any, definition: TransactionDefinition) {
            events.add("begin:$name")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit:$name")
            if (failure == "commit") {
                throw TransactionSystemException("synthetic commit failure")
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback:$name")
        }
    }

    companion object {
        fun problem(): DataAccessResourceFailureException {
            return DataAccessResourceFailureException("synthetic query failure")
        }

        private fun <T> path(): Path<T> {
            return mockk<Path<T>>()
        }

        private fun actorFor(state: String): StoreOrderAdministrators.Actor? {
            return when (state) {
                "null" -> null
                "wrong-id" -> StoreOrderAdministrators.Actor(8, true, false, "ROLE_ADMIN")
                "inactive" -> StoreOrderAdministrators.Actor(7, false, false, "ROLE_ADMIN")
                "deleted" -> StoreOrderAdministrators.Actor(7, true, true, "ROLE_ADMIN")
                else -> StoreOrderAdministrators.Actor(
                    7,
                    true,
                    false,
                    when (state) {
                        "user" -> "ROLE_USER"
                        "shelter" -> "ROLE_SHELTER"
                        "lowercase" -> "role_admin"
                        "space" -> "ROLE_ADMIN "
                        "prefix" -> "ROLE_ADMIN_MORE"
                        else -> null
                    },
                )
            }
        }

        @JvmStatic
        fun invalidActors(): Stream<Arguments> {
            return Stream.of("null", "wrong-id", "inactive", "deleted", "user", "shelter", "lowercase", "space", "prefix", "no-role")
                .flatMap { state -> Stream.of("list", "get", "write", "status").map { op -> Arguments.of(state, op) } }
        }

        @JvmStatic
        fun badQueries(): Stream<Arguments> {
            return Stream.concat(
                Stream.of(
                    "orderItems.price", "orderItemsPrice", "orderItems.order.orderItems.price", "user.id",
                    "sum(totalAmount)", "id DESC", " ", null,
                ).map { sort -> Arguments.of(AdminOrders.Query(null, null, null, null, sort, "asc", 0, 20)) },
                Stream.of(
                    Arguments.of(AdminOrders.Query(null, null, null, null, "id", "desc", -1, 20)),
                    Arguments.of(AdminOrders.Query(null, null, null, null, "id", "desc", 0, 0)),
                    Arguments.of(AdminOrders.Query(null, null, null, null, "id", "desc", 0, 101)),
                ),
            )
        }

        @JvmStatic
        fun failures(): Stream<Arguments> {
            return Stream.of("list", "get", "write", "status")
                .flatMap { op -> Stream.of("query", "commit").map { stage -> Arguments.of(op, stage) } }
        }

        @JvmStatic
        fun txModes(): Stream<Arguments> {
            return Stream.of("list", "get", "write", "status")
                .flatMap { op -> Stream.of("none", "wrong").map { kind -> Arguments.of(op, kind) } }
        }
    }
}
