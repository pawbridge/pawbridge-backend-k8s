package com.pawbridge.storeservice.domain.order.service;

import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.entity.DeliveryStatus;
import com.pawbridge.storeservice.domain.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 관리자용 주문 관리 서비스
 */
public interface AdminOrderService {
    
    /**
     * 전체 주문 목록 조회 (관리자용)
     */
    Page<OrderResponse> getAllOrders(
            OrderStatus status, 
            DeliveryStatus deliveryStatus, 
            Long userId,
            String keyword,
            String sortBy,
            String sortOrder,
            Pageable pageable);
    
    /**
     * 주문 상태 변경 (관리자용)
     */
    OrderResponse updateOrderStatus(Long orderId, OrderStatus status);
    
    /**
     * 배송 상태 변경 (관리자용)
     */
    OrderResponse updateDeliveryStatus(Long orderId, DeliveryStatus deliveryStatus);
}
