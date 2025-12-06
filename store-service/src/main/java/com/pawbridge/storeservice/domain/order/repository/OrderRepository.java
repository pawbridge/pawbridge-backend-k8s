package com.pawbridge.storeservice.domain.order.repository;

import com.pawbridge.storeservice.domain.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderUuid(String orderUuid);
}
