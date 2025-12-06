package com.pawbridge.storeservice.domain.product.repository;

import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductSKURepository extends JpaRepository<ProductSKU, Long> {
    Optional<ProductSKU> findBySkuCode(String skuCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductSKU s WHERE s.id = :id")
    Optional<ProductSKU> findByIdWithLock(@Param("id") Long id);
}
