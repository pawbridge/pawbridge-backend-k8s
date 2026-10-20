package com.pawbridge.backend.store.internal.cart.infrastructure.jpa.repository;

import com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUserId(Long userId);
}
