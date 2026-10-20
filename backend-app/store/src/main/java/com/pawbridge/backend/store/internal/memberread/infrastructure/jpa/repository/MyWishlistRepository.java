package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.repository;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Wishlist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

/**
 * 위시리스트 Repository
 * - 마이페이지용 + 찜 추가/삭제
 * - SKU 기반
 */
@Repository
public interface MyWishlistRepository extends JpaRepository<Wishlist, Long> {

    interface IdOnly { Long getId(); }

    Page<IdOnly> findIdsByUserId(Long userId, Pageable pageable);

    /** 선택한 사용자·찜 ID의 전체 원문 graph 조회. 부모 페이지에는 collection graph를 적용하지 않는다. */
    @EntityGraph(attributePaths = {"sku", "sku.product", "sku.product.category", "sku.skuValues", "sku.skuValues.optionValue", "sku.skuValues.optionValue.optionGroup"})
    List<Wishlist> findByUserIdAndIdIn(Long userId, Collection<Long> ids);

    /**
     * 사용자별 찜 개수
     */
    long countByUserId(Long userId);

    /**
     * 중복 찜 확인 (SKU 기반)
     */
    boolean existsByUserIdAndSkuId(Long userId, Long skuId);

    /**
     * 찜 조회 (삭제용, SKU 기반)
     */
    Optional<Wishlist> findByUserIdAndSkuId(Long userId, Long skuId);
}
