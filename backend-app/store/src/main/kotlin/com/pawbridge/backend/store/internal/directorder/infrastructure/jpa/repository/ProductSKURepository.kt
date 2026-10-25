package com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductSKU
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

// Nullable parameters: Spring Data rejects null for non-null Kotlin parameters, which the Java interface accepted.
interface ProductSKURepository : JpaRepository<ProductSKU, Long> {

    interface SkuProductId {
        val skuId: Long?
        val productId: Long?
    }

    fun findBySkuCode(skuCode: String?): Optional<ProductSKU>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductSKU s WHERE s.product.id = :productId ORDER BY s.id")
    fun findAllByProductIdWithLock(@Param("productId") productId: Long?): List<ProductSKU>

    @Query("SELECT s.id AS skuId, s.product.id AS productId FROM ProductSKU s WHERE s.id IN :skuIds ORDER BY s.product.id, s.id")
    fun findProductIdsBySkuIdIn(@Param("skuIds") skuIds: Collection<Long?>?): List<SkuProductId>

    @Query("SELECT DISTINCT s.product.id FROM ProductSKU s JOIN s.skuValues sv WHERE sv.optionValue.id = :optionValueId ORDER BY s.product.id")
    fun findProductIdsByOptionValueId(@Param("optionValueId") optionValueId: Long?): List<Long>

    @Query("SELECT DISTINCT s.product.id FROM ProductSKU s JOIN s.skuValues sv WHERE sv.optionValue.optionGroup.id = :optionGroupId ORDER BY s.product.id")
    fun findProductIdsByOptionGroupId(@Param("optionGroupId") optionGroupId: Long?): List<Long>
}
