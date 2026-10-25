package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Wishlist
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * 위시리스트 Repository
 * - 마이페이지용 + 찜 추가/삭제
 * - SKU 기반
 */
// Nullable parameters: Spring Data rejects null for non-null Kotlin parameters, which the Java interface accepted.
@Repository
interface MyWishlistRepository : JpaRepository<Wishlist, Long> {

    interface IdOnly {
        val id: Long?
    }

    fun findIdsByUserId(userId: Long?, pageable: Pageable?): Page<IdOnly>

    /** 선택한 사용자·찜 ID의 전체 원문 graph 조회. 부모 페이지에는 collection graph를 적용하지 않는다. */
    @EntityGraph(
        attributePaths = [
            "sku",
            "sku.product",
            "sku.product.category",
            "sku.skuValues",
            "sku.skuValues.optionValue",
            "sku.skuValues.optionValue.optionGroup",
        ],
    )
    fun findByUserIdAndIdIn(userId: Long?, ids: Collection<Long?>?): List<Wishlist>

    /**
     * 사용자별 찜 개수
     */
    fun countByUserId(userId: Long?): Long

    /**
     * 중복 찜 확인 (SKU 기반)
     */
    fun existsByUserIdAndSkuId(userId: Long?, skuId: Long?): Boolean

    /**
     * 찜 조회 (삭제용, SKU 기반)
     */
    fun findByUserIdAndSkuId(userId: Long?, skuId: Long?): Optional<Wishlist>
}
