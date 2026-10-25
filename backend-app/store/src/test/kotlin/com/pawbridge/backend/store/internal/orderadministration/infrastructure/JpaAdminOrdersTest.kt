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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
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
    val em: EntityManager = mock(EntityManager::class.java)
    val orders: OrderRepository = mock(OrderRepository::class.java)

    @Suppress("UNCHECKED_CAST")
    val itemFetch: TypedQuery<Order> = mock(TypedQuery::class.java) as TypedQuery<Order>
    val administrators: StoreOrderAdministrators = mock(StoreOrderAdministrators::class.java)
    val events = ArrayList<String>()
    val writes = TransactionTemplate(Manager("write"))
    val reads = TransactionTemplate(Manager("read"))
    val service = JpaAdminOrders(em, orders, writes, reads, administrators)
    val query = AdminOrders.Query(null, null, null, null, "createdAt", "desc", 0, 20)
    var actor: StoreOrderAdministrators.Actor? = StoreOrderAdministrators.Actor(7, true, false, "ROLE_ADMIN")
    lateinit var order: Order
    var failure = ""

    @BeforeEach
    fun setup() {
        reads.isReadOnly = true
        val entity = Order.builder().userId(88L).orderUuid("stored-uuid").totalAmount(9007199254740993L)
            .receiverName("stored receiver").receiverPhone("stored phone").deliveryAddress("stored address")
            .deliveryMessage("stored message").build()
        ReflectionTestUtils.setField(entity, "id", 33L)
        ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.of(2026, 10, 21, 2, 0, 1, 123456789))
        entity.orderItems.add(
            OrderItem.builder().order(entity).productName("stored product").skuCode("STORED-SKU")
                .price(9007199254740993L).quantity(1).build(),
        )
        order = spy(entity)
        doAnswer { call ->
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            events.add("map")
            call.callRealMethod()
        }.`when`(order).orderItems
        `when`(administrators.currentForRead(7)).thenAnswer {
            active(true)
            events.add("actor:read")
            actor
        }
        `when`(administrators.currentForWrite(7)).thenAnswer {
            active(false)
            events.add("actor:write")
            actor
        }
        doAnswer { call ->
            active(true)
            events.add("list")
            if (failure == "query") {
                throw problem()
            }
            PageImpl(listOf(order), call.getArgument<Pageable>(1), 1)
        }.`when`(orders).findAll(any<Specification<Order>>(), any<Pageable>())
        `when`(em.createQuery("select distinct o from Order o left join fetch o.orderItems where o.id in :ids", Order::class.java))
            .thenReturn(itemFetch)
        `when`(itemFetch.setParameter(eq("ids"), any<Any>())).thenReturn(itemFetch)
        `when`(itemFetch.resultList).thenAnswer {
            active(true)
            events.add("batch")
            listOf(order)
        }
        `when`(orders.findById(33L)).thenAnswer {
            active(true)
            events.add("get")
            if (failure == "query") {
                throw problem()
            }
            Optional.of(order)
        }
        `when`(em.find(Order::class.java, 33L, LockModeType.PESSIMISTIC_WRITE)).thenAnswer {
            active(false)
            events.add("order-lock")
            if (failure == "query") {
                throw problem()
            }
            order
        }
        doAnswer {
            active(false)
            events.add("flush")
            if (order.deliveryStatus == null || order.status == null || failure == "flush") {
                throw PersistenceException("synthetic constraint failure")
            }
            null
        }.`when`(em).flush()
    }

    @AfterEach
    fun close() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    fun active(readOnly: Boolean) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertEquals(readOnly, TransactionSynchronizationManager.isCurrentTransactionReadOnly())
    }

    fun access(op: String) {
        when (op) {
            "list" -> service.list(7, query)
            "get" -> service.get(7, 33)
            else -> service.updateDelivery(7, 33, "SHIPPING")
        }
    }

    @Test
    fun given__current_database_admin__when__reading_other_members_order__then__preserve_full_snapshot_inside_read_only_transaction() {
        val result = service.get(7, 33)

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
        verifyNoInteractions(em)
        verify(administrators, never()).currentForWrite(anyLong())
    }

    @ParameterizedTest
    @ValueSource(strings = ["READY", "SHIPPING", "DELIVERED"])
    fun given__admin_and_locked_order__when__changing_delivery__then__only_delivery_changes_after_user_then_order_lock_and_flush(
        delivery: String,
    ) {
        val result = service.updateDelivery(7, 33, delivery)

        assertEquals(delivery, result.deliveryStatus)
        assertEquals("PENDING", result.status)
        assertEquals(9007199254740993L, result.totalAmount)
        assertEquals(88L, result.userId)
        assertEquals("stored-uuid", result.orderUuid)
        assertEquals("stored address", result.deliveryAddress)
        assertEquals(1, result.items!!.size)
        assertEquals(listOf("begin:write", "actor:write", "order-lock", "flush", "map", "commit:write"), events)
        verify(em).find(Order::class.java, 33L, LockModeType.PESSIMISTIC_WRITE)
        verify(em).flush()
        verifyNoMoreInteractions(em)
        verifyNoInteractions(orders)
        verify(administrators, never()).currentForRead(anyLong())
    }

    @ParameterizedTest
    @ValueSource(strings = ["PENDING", "PAID", "COMPLETED", "CANCELLED", "FAILED"])
    fun given__admin_and_locked_order__when__changing_status__then__only_status_changes_after_user_then_order_lock_and_flush(
        status: String,
    ) {
        val result = service.updateStatus(7, 33, status)

        assertEquals(status, result.status)
        assertEquals("READY", result.deliveryStatus)
        assertEquals(9007199254740993L, result.totalAmount)
        assertEquals(88L, result.userId)
        assertEquals("stored-uuid", result.orderUuid)
        assertEquals("stored address", result.deliveryAddress)
        assertEquals(1, result.items!!.size)
        assertEquals(listOf("begin:write", "actor:write", "order-lock", "flush", "map", "commit:write"), events)
        verify(em).find(Order::class.java, 33L, LockModeType.PESSIMISTIC_WRITE)
        verify(em).flush()
        verifyNoMoreInteractions(em)
        verifyNoInteractions(orders)
        verify(administrators, never()).currentForRead(anyLong())
    }

    @ParameterizedTest
    @MethodSource("invalidActors")
    fun given__ineligible_current_database_admin__when__changing_status__then__deny_before_order_rows(
        state: String,
        op: String,
    ) {
        if (op != "write") {
            return
        }
        actor = actorFor(state)

        assertThrows(AdministratorRejectedException::class.java) { service.updateStatus(7, 33, "PAID") }

        verifyNoInteractions(em, orders)
        assertEquals("rollback:write", events.last())
    }

    @Test
    fun given__missing_order__when__changing_status__then__original_400_family_without_flush() {
        doReturn(null).`when`(em).find(Order::class.java, 33L, LockModeType.PESSIMISTIC_WRITE)

        assertThrows(IllegalArgumentException::class.java) { service.updateStatus(7, 33, "PAID") }

        verify(em, never()).flush()
        assertEquals("rollback:write", events.last())
    }

    @Test
    fun given__null_status__when__updating__then__generic_failure_after_actor_and_order_lock_without_update_or_flush() {
        val failure = assertThrows(IllegalStateException::class.java) { service.updateStatus(7, 33, null) }

        assertEquals("Order status cannot be stored", failure.message)
        assertEquals("PENDING", order.status!!.name)
        assertEquals(listOf("begin:write", "actor:write", "order-lock", "rollback:write"), events)
        verify(order, never()).updateStatus(nullable(OrderStatus::class.java))
        verify(em, never()).flush()
    }

    @Test
    fun given__flush_or_commit_failure__when__changing_status__then__propagate_without_success() {
        failure = "flush"
        assertThrows(PersistenceException::class.java) { service.updateStatus(7, 33, "CANCELLED") }
        assertEquals("rollback:write", events.last())
        events.clear()
        failure = "commit"
        assertThrows(TransactionSystemException::class.java) { service.updateStatus(7, 33, "CANCELLED") }
    }

    @Test
    fun given__missing_or_read_only_transaction__when__changing_status__then__fail_before_admin_or_order_lookup() {
        val none = JpaAdminOrders(em, orders, TransactionOperations.withoutTransaction(), reads, administrators)

        assertThrows(IllegalStateException::class.java) { none.updateStatus(7, 33, "PAID") }
        writes.isReadOnly = true
        assertThrows(IllegalStateException::class.java) { service.updateStatus(7, 33, "PAID") }

        verifyNoInteractions(em, orders, administrators)
    }

    @ParameterizedTest
    @MethodSource("invalidActors")
    fun given__ineligible_or_nonexact_current_database_admin__when__using_any_operation__then__deny_before_order_rows(
        state: String,
        op: String,
    ) {
        actor = actorFor(state)

        assertThrows(AdministratorRejectedException::class.java) { access(op) }

        verifyNoInteractions(em, orders)
        assertEquals("rollback:" + (if (op == "write") "write" else "read"), events.last())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "id", "userId", "orderUuid", "totalAmount", "status", "deliveryAddress", "deliveryStatus",
            "receiverName", "receiverPhone", "deliveryMessage", "createdAt", "updatedAt",
        ],
    )
    fun given__existing_root_scalar_sort__when__listing__then__original_repository_spec_and_page_metadata_inside_read_transaction(
        property: String,
    ) {
        val input = AdminOrders.Query(null, null, null, null, property, "ASC", 0, 20)

        val result = service.list(7, input)

        assertEquals(1, result.content!!.size)
        assertEquals(1L, result.totalElements)
        assertEquals(20, result.size)
        assertEquals(0, result.number)
        assertEquals(property, result.sortBy)
        assertTrue(result.ascending)
        verify(orders).findAll(
            any<Specification<Order>>(),
            eq(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, property))),
        )
        verify(em).createQuery("select distinct o from Order o left join fetch o.orderItems where o.id in :ids", Order::class.java)
        verify(itemFetch).setParameter("ids", listOf(33L))
        verify(itemFetch).resultList
        verifyNoMoreInteractions(em, itemFetch)
        assertEquals(listOf("begin:read", "actor:read", "list", "batch", "map", "commit:read"), events)
    }

    @Test
    fun given__nonempty_page__when__batch_loading_items__then__preserve_original_order_metadata_and_snapshots() {
        val second = Order.builder().userId(99L).orderUuid("second-uuid").totalAmount(1400L)
            .receiverName("second receiver").receiverPhone("second phone").deliveryAddress("second address").build()
        ReflectionTestUtils.setField(second, "id", 22L)
        second.orderItems.add(
            OrderItem.builder().order(second).productName("second product").skuCode("SECOND-SKU").price(700L).quantity(2).build(),
        )
        val pageable = PageRequest.of(2, 2, Sort.by(Sort.Direction.DESC, "id"))
        doAnswer {
            active(true)
            events.add("list")
            PageImpl(listOf(order, second), pageable, 77)
        }.`when`(orders).findAll(any<Specification<Order>>(), eq(pageable))
        doAnswer {
            active(true)
            events.add("batch")
            listOf(second, order)
        }.`when`(itemFetch).resultList

        val result = service.list(7, AdminOrders.Query(null, null, null, null, "id", "desc", 2, 2))

        assertEquals(listOf(33L, 22L), result.content!!.map { item -> item.orderId })
        assertEquals(77L, result.totalElements)
        assertEquals(2, result.number)
        assertEquals(2, result.size)
        assertEquals("id", result.sortBy)
        assertFalse(result.ascending)
        assertEquals(9007199254740993L, result.content!!.first().items!!.first().price)
        assertEquals("second product", result.content!!.last().items!!.first().productName)
        assertEquals(2, result.content!!.last().items!!.first().quantity)
        verify(itemFetch).setParameter("ids", listOf(33L, 22L))
        verify(itemFetch).resultList
        verify(em).createQuery("select distinct o from Order o left join fetch o.orderItems where o.id in :ids", Order::class.java)
        verifyNoMoreInteractions(em, itemFetch)
        assertEquals(listOf("begin:read", "actor:read", "list", "batch", "map", "commit:read"), events)
    }

    @Test
    fun given__empty_page__when__batch_loading_items__then__skip_fetch_and_preserve_metadata() {
        val pageable = PageRequest.of(4, 20, Sort.by(Sort.Direction.DESC, "id"))
        doAnswer {
            active(true)
            events.add("list")
            PageImpl<Order>(listOf(), pageable, 77)
        }.`when`(orders).findAll(any<Specification<Order>>(), eq(pageable))

        val result = service.list(7, AdminOrders.Query(null, null, null, null, "id", "desc", 4, 20))

        assertTrue(result.content!!.isEmpty())
        assertEquals(77L, result.totalElements)
        assertEquals(4, result.number)
        assertEquals(20, result.size)
        verifyNoInteractions(em, itemFetch)
        assertEquals(listOf("begin:read", "actor:read", "list", "commit:read"), events)
    }

    @Test
    fun given__batch_fetch_failure__when__listing__then__rollback_without_mapping_or_partial_response() {
        doAnswer {
            active(true)
            events.add("batch")
            throw problem()
        }.`when`(itemFetch).resultList

        assertThrows(DataAccessResourceFailureException::class.java) { service.list(7, query) }

        assertEquals(listOf("begin:read", "actor:read", "list", "batch", "rollback:read"), events)
        verify(order, never()).orderItems
    }

    @ParameterizedTest
    @MethodSource("badQueries")
    fun given__unsupported_sort_or_page_resource_bound__when__listing__then__fixed_bad_input_before_transaction_or_admin_lookup(
        input: AdminOrders.Query,
    ) {
        val failure = assertThrows(IllegalArgumentException::class.java) { service.list(7, input) }

        assertEquals("Admin order query is not supported", failure.message)
        assertTrue(events.isEmpty())
        verifyNoInteractions(em, orders, administrators)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ASC", "desc", "anything"])
    fun given__original_sort_order_text__when__listing_at_size_bound__then__only_asc_means_ascending(direction: String) {
        val result = service.list(7, AdminOrders.Query(null, null, null, null, "id", direction, 0, 100))

        assertEquals("ASC" == direction, result.ascending)
        assertEquals(100, result.size)
    }

    @Test
    fun given__all_filters_and_wildcard_keyword__when__building_specification__then__preserve_original_fields_and_unescaped_like_pattern() {
        service.list(7, AdminOrders.Query("PAID", "SHIPPING", 99L, " A%_ B ", "createdAt", "desc", 0, 20))
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Specification::class.java) as ArgumentCaptor<Specification<Order>>
        verify(orders).findAll(captor.capture(), any<Pageable>())
        @Suppress("UNCHECKED_CAST")
        val root = mock(Root::class.java) as Root<Order>
        val query = mock(CriteriaQuery::class.java)
        val cb = mock(CriteriaBuilder::class.java)
        val predicate = mock(Predicate::class.java)
        val status = path<Any>()
        val delivery = path<Any>()
        val user = path<Any>()
        val uuid = path<String>()
        val receiver = path<String>()
        `when`(root.get<Any>("status")).thenReturn(status)
        `when`(root.get<Any>("deliveryStatus")).thenReturn(delivery)
        `when`(root.get<Any>("userId")).thenReturn(user)
        `when`(root.get<String>("orderUuid")).thenReturn(uuid)
        `when`(root.get<String>("receiverName")).thenReturn(receiver)
        `when`(cb.equal(any<Expression<*>>(), any<Expression<*>>())).thenReturn(predicate)
        `when`(cb.lower(any<Expression<String>>())).thenAnswer { call -> call.getArgument<Any>(0) }
        `when`(cb.like(any<Expression<String>>(), anyString())).thenReturn(predicate)
        `when`(cb.and(any<Expression<Boolean>>(), any<Expression<Boolean>>())).thenReturn(predicate)
        `when`(cb.or(any<Expression<Boolean>>(), any<Expression<Boolean>>())).thenReturn(predicate)

        captor.value.toPredicate(root, query, cb)

        verify(cb).equal(status, OrderStatus.PAID)
        verify(cb).equal(delivery, DeliveryStatus.SHIPPING)
        verify(cb).equal(user, 99L)
        verify(cb).like(uuid, "% a%_ b %")
        verify(cb).like(receiver, "% a%_ b %")
    }

    @Test
    fun given__blank_keyword__when__building_specification__then__do_not_add_like_predicates() {
        service.list(7, AdminOrders.Query(null, null, null, "  ", "id", "desc", 0, 20))
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Specification::class.java) as ArgumentCaptor<Specification<Order>>
        verify(orders).findAll(captor.capture(), any<Pageable>())
        @Suppress("UNCHECKED_CAST")
        val root = mock(Root::class.java) as Root<Order>
        val cb = mock(CriteriaBuilder::class.java)

        assertNull(captor.value.toPredicate(root, mock(CriteriaQuery::class.java), cb))

        verifyNoInteractions(root, cb)
    }

    @Test
    fun given__missing_order__when__reading_and_updating__then__distinct_original_404_and_400_families() {
        doReturn(Optional.empty<Order>()).`when`(orders).findById(33L)
        doReturn(null).`when`(em).find(Order::class.java, 33L, LockModeType.PESSIMISTIC_WRITE)

        assertThrows(AdminOrderNotFoundException::class.java) { service.get(7, 33) }
        assertThrows(IllegalArgumentException::class.java) { service.updateDelivery(7, 33, "SHIPPING") }

        verify(em, never()).flush()
    }

    @Test
    fun given__null_delivery_status__when__updating__then__generic_failure_after_actor_and_order_lock_without_update_or_flush() {
        val failure = assertThrows(IllegalStateException::class.java) { service.updateDelivery(7, 33, null) }

        assertEquals("Delivery status cannot be stored", failure.message)
        assertEquals(DeliveryStatus.READY, order.deliveryStatus)
        assertEquals(listOf("begin:write", "actor:write", "order-lock", "rollback:write"), events)
        verify(order, never()).updateDeliveryStatus(nullable(DeliveryStatus::class.java))
        verify(em, never()).flush()
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun given__query_or_commit_failure__when__administering_orders__then__propagate_without_success(op: String, stage: String) {
        failure = stage
        val type: Class<out RuntimeException> = if (stage == "commit") {
            TransactionSystemException::class.java
        } else {
            DataAccessResourceFailureException::class.java
        }

        assertThrows(type) { access(op) }
    }

    @Test
    fun given__flush_failure__when__updating_delivery__then__propagate_and_request_rollback() {
        failure = "flush"

        assertThrows(PersistenceException::class.java) { service.updateDelivery(7, 33, "DELIVERED") }

        assertEquals("rollback:write", events.last())
    }

    @ParameterizedTest
    @MethodSource("txModes")
    fun given__missing_or_wrong_transaction__when__administering_orders__then__fail_before_admin_or_order_lookup(
        op: String,
        kind: String,
    ) {
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

        assertThrows(IllegalStateException::class.java) {
            when (op) {
                "list" -> service.list(7, query)
                "get" -> service.get(7, 33)
                else -> service.updateDelivery(7, 33, "SHIPPING")
            }
        }

        verifyNoInteractions(em, orders, administrators)
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

        @Suppress("UNCHECKED_CAST")
        private fun <T> path(): Path<T> {
            return mock(Path::class.java) as Path<T>
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
                .flatMap { state -> Stream.of("list", "get", "write").map { op -> Arguments.of(state, op) } }
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
            return Stream.of("list", "get", "write")
                .flatMap { op -> Stream.of("query", "commit").map { stage -> Arguments.of(op, stage) } }
        }

        @JvmStatic
        fun txModes(): Stream<Arguments> {
            return Stream.of("list", "get", "write")
                .flatMap { op -> Stream.of("none", "wrong").map { kind -> Arguments.of(op, kind) } }
        }
    }
}
