package com.pawbridge.storeservice.domain.mypage.repository;

import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ProductSKU Repository
 * - 마이페이지용
 */
@Repository
public interface MyProductSKURepository extends JpaRepository<ProductSKU, Long> {

    /**
     * ProductSKU를 Product와 함께 조회 (fetch join)
     * - N+1 문제 방지
     * @param ids ProductSKU ID 목록
     * @return ProductSKU 목록
     */
    @Query("SELECT s FROM ProductSKU s JOIN FETCH s.product WHERE s.id IN :ids")
    List<ProductSKU> findAllByIdWithProduct(@Param("ids") List<Long> ids);
}
