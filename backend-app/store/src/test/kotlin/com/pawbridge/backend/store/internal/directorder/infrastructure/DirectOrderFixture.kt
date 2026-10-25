package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.Optional

internal class DirectOrderFixture : AutoCloseable {
    val events = ArrayList<String>()
    val writer = Manager("writer")
    val reader = Manager("reader")
    val write = TransactionTemplate(writer)
    val read = TransactionTemplate(reader)
    val writerOrders: OrderRepository = mock(OrderRepository::class.java)
    val readerOrders: OrderRepository = mock(OrderRepository::class.java)
    val products: ProductRepository = mock(ProductRepository::class.java)
    val skus: ProductSKURepository = mock(ProductSKURepository::class.java)
    val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    val actors: StoreOrderActors = mock(StoreOrderActors::class.java)
    val terms = KoreanSearchTerms()
    val product: Product = Product.builder().name("fixture product").status(ProductStatus.ACTIVE).build()
    val target: ProductSKU = ProductSKU.builder().product(product).skuCode("CURRENT-80").price(100L).stockQuantity(5).build()
    val sibling: ProductSKU = ProductSKU.builder().product(product).skuCode("CURRENT-81").price(50L).stockQuantity(8).build()
    val orders = LinkedHashMap<Long, Order>()
    val searchParameters = ArrayList<Map<String, *>>()
    val time: LocalDateTime = LocalDateTime.of(2026, 10, 20, 22, 0, 1, 123456789)
    val publisher: ProductSnapshots
    val stocks: JpaStockAdjustments
    val service: JpaDirectOrders
    var actor: StoreOrderActors.Actor? = StoreOrderActors.Actor(7, true, false)
    var failAt = ""
    var identity = 33L

    init {
        write.setName("writer")
        read.setName("reader")
        read.isReadOnly = true
        id(product, 90)
        id(target, 80)
        id(sibling, 81)
        product.skus!!.addAll(listOf(target, sibling))
        ReflectionTestUtils.setField(product, "createdAt", time)
        val reference = mock(ProductSKURepository.SkuProductId::class.java)
        `when`(reference.skuId).thenReturn(80L)
        `when`(reference.productId).thenReturn(90L)
        `when`(actors.currentForWrite(7)).thenAnswer {
            writer()
            events.add("actor:write")
            actor
        }
        `when`(actors.currentForRead(7)).thenAnswer {
            reader()
            events.add("actor:read")
            actor
        }
        `when`(skus.findProductIdsBySkuIdIn(anyCollection())).thenAnswer {
            writer()
            events.add("reference")
            listOf(reference)
        }
        `when`(products.findByIdWithLock(90L)).thenAnswer {
            writer()
            events.add("product-lock")
            Optional.of(product)
        }
        `when`(skus.findAllByProductIdWithLock(90L)).thenAnswer {
            writer()
            events.add("all-sku-lock")
            listOf(target, sibling)
        }
        `when`(skus.findById(80L)).thenAnswer {
            writer()
            events.add("sku-read")
            Optional.of(target)
        }
        `when`(jdbc.update(anyString(), anyMap<String, Any>())).thenAnswer { call ->
            writer()
            events.add("search")
            if (failAt == "search") {
                throw failure()
            }
            searchParameters.add(HashMap<String, Any?>(call.getArgument<Map<String, Any?>>(1)))
            1
        }
        `when`(writerOrders.save(any(Order::class.java))).thenAnswer { call ->
            writer()
            events.add("order-save")
            if (failAt == "order") {
                throw failure()
            }
            val order = call.getArgument<Order>(0)
            id(order, identity++)
            ReflectionTestUtils.setField(order, "createdAt", time)
            orders[order.id!!] = order
            order
        }
        `when`(readerOrders.findById(anyLong())).thenAnswer { call ->
            reader()
            events.add("order-read")
            Optional.ofNullable(orders[call.getArgument<Long>(0)])
        }
        publisher = ProductSnapshots(PostgresqlSearchDocuments(jdbc, terms))
        stocks = JpaStockAdjustments(products, skus, publisher)
        service = JpaDirectOrders(writerOrders, readerOrders, skus, stocks, actors, write, read)
    }

    fun command(quantity: Int): DirectOrders.Command {
        return DirectOrders.Command(80L, quantity, "fixture receiver", "fixture phone", "fixture address", "fixture message")
    }

    fun writer() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertEquals("writer", TransactionSynchronizationManager.getCurrentTransactionName())
    }

    fun reader() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertEquals("reader", TransactionSynchronizationManager.getCurrentTransactionName())
    }

    override fun close() {
        terms.close()
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
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
            if (failAt == "commit") {
                throw TransactionSystemException("synthetic commit failure")
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback:$name")
        }
    }

    companion object {
        fun id(entity: Any, id: Long) {
            ReflectionTestUtils.setField(entity, "id", id)
        }

        fun failure(): DataAccessResourceFailureException {
            return DataAccessResourceFailureException("synthetic persistence failure")
        }
    }
}
