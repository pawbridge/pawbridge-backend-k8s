package com.pawbridge.backend.store.internal.cart.infrastructure

import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException
import com.pawbridge.backend.store.internal.cart.application.Carts
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.projection.CartItemResponse
import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository.CartRepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

/** DB writes replace Redis hot data; Root fences the old scheduler and owns legacy data handoff. */
class JpaCarts internal constructor(
    private val em: EntityManager,
    private val carts: CartRepository,
    private val writes: TransactionOperations,
    private val reads: TransactionOperations,
    private val actors: StoreOrderActors,
) : Carts {

    constructor(
        sharedEntityManager: EntityManager,
        writes: TransactionOperations,
        reads: TransactionOperations,
        actors: StoreOrderActors,
    ) : this(
        sharedEntityManager,
        JpaRepositoryFactory(sharedEntityManager).getRepository(CartRepository::class.java),
        writes,
        reads,
        actors,
    )

    override fun get(verifiedUserId: Long): List<Carts.Item> =
        reads.execute {
            transaction(true)
            actor(verifiedUserId, actors.currentForRead(verifiedUserId))
            val cart = carts.findByUserId(verifiedUserId)
            if (cart.isEmpty || cart.get().items.isEmpty()) {
                listOf()
            } else {
                val quantities = items(cart.get())
                em.createQuery("select s from ProductSKU s where s.id in :ids", ProductSKU::class.java)
                    .setParameter("ids", quantities.keys)
                    .resultList
                    .stream()
                    .map { sku ->
                        val quantity = quantities[sku.id]!!.quantity!!
                        amount(sku, quantity)
                        val row = CartItemResponse.of(sku, quantity)
                        Carts.Item(row.id, row.skuId, row.skuCode, row.productName, row.price, row.quantity, row.totalPrice)
                    }
                    .toList()
            }
        }!!

    override fun add(verifiedUserId: Long, skuId: Long?, quantity: Int?) {
        write(verifiedUserId) {
            if (skuId == null || skuId <= 0 || quantity == null || quantity <= 0) {
                throw IllegalArgumentException("SKU ID and quantity must be positive")
            }
            val sku = em.find(ProductSKU::class.java, skuId)
                ?: throw IllegalArgumentException("SKU not found")
            // Preserve the original per-add stock check; cart quantity is not a stock reservation.
            if (sku.stockQuantity!! < quantity) {
                throw IllegalArgumentException("Not enough stock")
            }
            amount(sku, quantity)
            val cart = carts.findByUserId(verifiedUserId).orElseGet {
                carts.save(Cart.builder().userId(verifiedUserId).build())
            }
            val existing = items(cart)[skuId]
            if (existing == null) {
                cart.addItem(CartItem.builder().skuId(skuId).quantity(quantity).build())
            } else {
                val next: Int
                try {
                    next = Math.addExact(existing.quantity!!, quantity)
                } catch (failure: ArithmeticException) {
                    throw IllegalArgumentException("Cart quantity is out of range", failure)
                }
                amount(sku, next)
                existing.updateQuantity(next)
            }
        }
    }

    override fun update(verifiedUserId: Long, skuId: Long, quantity: Int) {
        write(verifiedUserId) {
            val cart = carts.findByUserId(verifiedUserId)
            if (quantity <= 0) {
                cart.ifPresent { value -> value.items.removeIf { item -> item.skuId!! == skuId } }
                return@write
            }
            val existing = cart.map { value -> items(value) }.map { value -> value[skuId] }.orElse(null)
                ?: throw IllegalArgumentException("Item not in cart")
            val sku = em.find(ProductSKU::class.java, skuId)
            if (sku != null) {
                amount(sku, quantity)
            }
            existing.updateQuantity(quantity)
        }
    }

    override fun remove(verifiedUserId: Long, skuId: Long) {
        write(verifiedUserId) {
            carts.findByUserId(verifiedUserId).ifPresent { cart ->
                cart.items.removeIf { item -> item.skuId!! == skuId }
            }
        }
    }

    override fun clear(verifiedUserId: Long) {
        write(verifiedUserId) {
            carts.findByUserId(verifiedUserId).ifPresent { cart -> cart.clearItems() }
        }
    }

    private fun write(user: Long, change: () -> Unit) {
        writes.execute<Any?> {
            transaction(false)
            actor(user, actors.currentForWrite(user))
            change()
            null
        }
    }

    private companion object {
        private fun amount(sku: ProductSKU, quantity: Int) {
            try {
                Math.multiplyExact(sku.price!!, quantity.toLong())
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("Cart amount is out of range", failure)
            }
        }

        private fun items(cart: Cart): Map<Long?, CartItem> {
            val result = LinkedHashMap<Long?, CartItem>()
            for (item in cart.items) {
                if (result.putIfAbsent(item.skuId, item) != null) {
                    throw PersistenceException("Stored cart cannot be read")
                }
            }
            return result
        }

        private fun actor(user: Long, actor: StoreOrderActors.Actor?) {
            if (actor == null || actor.userId != user || !actor.active || actor.deleted) {
                throw CartActorRejectedException()
            }
        }

        private fun transaction(readOnly: Boolean) {
            if (!TransactionSynchronizationManager.isActualTransactionActive() ||
                TransactionSynchronizationManager.isCurrentTransactionReadOnly() != readOnly
            ) {
                throw IllegalStateException("An active cart transaction with the expected read mode is required")
            }
        }
    }
}
