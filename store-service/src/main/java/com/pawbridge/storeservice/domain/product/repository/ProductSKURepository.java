package com.pawbridge.storeservice.domain.product.repository;

import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductSKURepository extends JpaRepository<ProductSKU, Long> {
    interface SkuProductId {
        Long getSkuId();
        Long getProductId();
    }

    Optional<ProductSKU> findBySkuCode(String skuCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductSKU s WHERE s.product.id = :productId ORDER BY s.id")
    List<ProductSKU> findAllByProductIdWithLock(@Param("productId") Long productId);

    @Query("SELECT s.id AS skuId, s.product.id AS productId FROM ProductSKU s WHERE s.id IN :skuIds ORDER BY s.product.id, s.id")
    List<SkuProductId> findProductIdsBySkuIdIn(@Param("skuIds") Collection<Long> skuIds);

    @Query("SELECT DISTINCT s.product.id FROM ProductSKU s JOIN s.skuValues sv WHERE sv.optionValue.id = :optionValueId ORDER BY s.product.id")
    List<Long> findProductIdsByOptionValueId(@Param("optionValueId") Long optionValueId);

    @Query("SELECT DISTINCT s.product.id FROM ProductSKU s JOIN s.skuValues sv WHERE sv.optionValue.optionGroup.id = :optionGroupId ORDER BY s.product.id")
    List<Long> findProductIdsByOptionGroupId(@Param("optionGroupId") Long optionGroupId);
}
