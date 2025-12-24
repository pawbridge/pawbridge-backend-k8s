package com.pawbridge.storeservice.domain.cart.repository;

import com.pawbridge.storeservice.domain.cart.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    
    /**
     * 해당 SKU ID 목록 중 하나라도 장바구니에 있는지 확인
     */
    boolean existsByProductSkuIdIn(List<Long> skuIds);
}
