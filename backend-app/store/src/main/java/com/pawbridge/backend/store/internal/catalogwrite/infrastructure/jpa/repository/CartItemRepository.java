package com.pawbridge.backend.store.internal.catalogwrite.infrastructure.jpa.repository;

import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;


import java.util.List;


public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    
    /**
     * 해당 SKU ID 목록 중 하나라도 장바구니에 있는지 확인
     */
    boolean existsByProductSkuIdIn(List<Long> skuIds);
}
