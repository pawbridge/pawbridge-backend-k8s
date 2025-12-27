package com.pawbridge.storeservice.domain.mypage.repository;

import com.pawbridge.storeservice.domain.product.entity.Wishlist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 위시리스트 Repository
 * - 마이페이지용 + 찜 추가/삭제
 * - SKU 기반
 */
@Repository
public interface MyWishlistRepository extends JpaRepository<Wishlist, Long> {

    /**
     * 사용자별 찜 목록 조회 (SKU, Product, Category fetch join)
     */
    @EntityGraph(attributePaths = {"sku", "sku.product", "sku.product.category", "sku.skuValues", "sku.skuValues.optionValue", "sku.skuValues.optionValue.optionGroup"})
    Page<Wishlist> findByUserId(Long userId, Pageable pageable);

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
