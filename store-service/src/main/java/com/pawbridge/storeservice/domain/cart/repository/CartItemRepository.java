package com.pawbridge.storeservice.domain.cart.repository;

import com.pawbridge.storeservice.domain.cart.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
}
