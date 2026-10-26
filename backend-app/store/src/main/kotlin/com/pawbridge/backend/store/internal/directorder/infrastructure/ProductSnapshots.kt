package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Product
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations

/** Original ProductOutboxService search snapshot and ProductSKUService.findPrimarySku; the SKU_UPDATED outbox was removed with the event queue. */
class ProductSnapshots internal constructor(
    private val documents: PostgresqlSearchDocuments,
) {

    constructor(
        search: NamedParameterJdbcOperations,
        terms: KoreanSearchTerms,
    ) : this(PostgresqlSearchDocuments(search, terms))

    fun publishProductSnapshot(product: Product) {
        if (product.skus!!.isEmpty()) {
            documents.delete(product.id!!)
            return
        }
        val primary = findPrimarySku(product.skus)!!
        documents.write(product.id!!, primary.id!!, product.name, primary.generateOptionName())
    }

    private companion object {
        private fun findPrimarySku(skus: List<ProductSKU>?): ProductSKU? {
            if (skus == null || skus.isEmpty()) {
                return null
            }
            return skus.stream().min { first, second ->
                val compared = first.price!!.compareTo(second.price!!)
                if (compared != 0) {
                    compared
                } else if (first.id == null || second.id == null) {
                    0
                } else {
                    first.id!!.compareTo(second.id!!)
                }
            }.orElse(skus[0])
        }
    }
}
