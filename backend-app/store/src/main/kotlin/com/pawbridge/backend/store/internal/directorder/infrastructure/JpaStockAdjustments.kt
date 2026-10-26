package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus
import java.util.stream.Collectors

/** Selected original stock business; locks remain held until the caller's writer transaction completes. */
class JpaStockAdjustments(
    private val products: ProductRepository,
    private val skus: ProductSKURepository,
    private val snapshots: ProductSnapshots,
) {

    internal fun decreaseStocks(quantities: Map<Long?, Int?>?) {
        adjustStocks(quantities, true)
    }

    fun increaseStocks(quantities: Map<Long?, Int?>?) {
        adjustStocks(quantities, false)
    }

    private fun adjustStocks(quantities: Map<Long?, Int?>?, decrease: Boolean) {
        if (quantities == null || quantities.isEmpty()) {
            return
        }
        for ((id, quantity) in quantities) {
            if (id == null || quantity == null || quantity <= 0) {
                throw IllegalArgumentException("SKU ID and quantity must be positive")
            }
        }
        val references = skus.findProductIdsBySkuIdIn(quantities.keys)
        if (references.size != quantities.size) {
            throw IllegalArgumentException("A requested SKU does not exist")
        }
        val byProduct = HashMap<Long?, MutableList<Long?>>()
        for (reference in references) {
            byProduct.computeIfAbsent(reference.productId) { ArrayList() }.add(reference.skuId)
        }
        // Lock products in ascending id order so concurrent orders cannot deadlock.
        byProduct.entries
            .sortedWith { first, second -> first.key!!.compareTo(second.key!!) }
            .forEach { entry -> adjustProductStocks(entry.key, entry.value, quantities, decrease) }
    }

    private fun adjustProductStocks(
        productId: Long?,
        targets: List<Long?>,
        quantities: Map<Long?, Int?>,
        decrease: Boolean,
    ) {
        val product = products.findByIdWithLock(productId)
            .orElseThrow { IllegalArgumentException("Product not found") }
        val locked = skus.findAllByProductIdWithLock(productId)
        val byId = locked.stream().collect(Collectors.toMap({ sku -> sku.id }, { sku -> sku }))
        targets
            .sortedWith { first, second -> first!!.compareTo(second!!) }
            .forEach { id ->
                val sku: ProductSKU = byId[id]
                    ?: throw IllegalArgumentException("SKU not found after product lock")
                if (decrease && product.status != ProductStatus.ACTIVE) {
                    throw IllegalStateException("Product cannot be ordered in its current state")
                }
                if (decrease) {
                    sku.decreaseStock(quantities[id]!!)
                } else {
                    sku.increaseStock(quantities[id]!!)
                }
            }
        snapshots.publishProductSnapshot(product)
        // Approved difference: this module has no Redis product cache consumer; eviction is an explicit no-op.
    }
}
