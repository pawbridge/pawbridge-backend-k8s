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
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

internal class DirectOrderFixture : AutoCloseable {
    val events = ArrayList<String>()
    val writer = Manager("writer")
    val reader = Manager("reader")
    val write = TransactionTemplate(writer)
    val read = TransactionTemplate(reader)
    val writerOrders = mockk<OrderRepository>()
    val readerOrders = mockk<OrderRepository>()
    val products = mockk<ProductRepository>()
    val skus = mockk<ProductSKURepository>()
    val jdbc = mockk<NamedParameterJdbcOperations>()
    val actors = mockk<StoreOrderActors>()
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

/** Plain data stand-in for the repository projection that pairs a SKU with its product. */
internal data class SkuReference(
    override val skuId: Long?,
    override val productId: Long?,
) : ProductSKURepository.SkuProductId
