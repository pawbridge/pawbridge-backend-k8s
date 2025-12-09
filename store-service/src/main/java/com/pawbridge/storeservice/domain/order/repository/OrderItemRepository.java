package com.pawbridge.storeservice.domain.order.repository;

import com.pawbridge.storeservice.domain.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
