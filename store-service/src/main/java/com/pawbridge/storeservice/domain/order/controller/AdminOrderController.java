package com.pawbridge.storeservice.domain.order.controller;

import com.pawbridge.storeservice.domain.order.dto.DeliveryStatusUpdateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.dto.OrderStatusUpdateRequest;
import com.pawbridge.storeservice.domain.order.entity.DeliveryStatus;
import com.pawbridge.storeservice.domain.order.entity.OrderStatus;
import com.pawbridge.storeservice.domain.order.service.AdminOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자용 주문 관리 API
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    /**
     * 전체 주문 목록 조회 (관리자용)
     * - 필터링: status, deliveryStatus, userId
     * - 검색: keyword (주문번호, 수령인 이름)
     * - 정렬: sortBy, sortOrder
     */
    @GetMapping
    public ResponseEntity<Page<OrderResponse>> getAllOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) DeliveryStatus deliveryStatus,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortOrder,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<OrderResponse> response = adminOrderService.getAllOrders(
                status, deliveryStatus, userId, keyword, sortBy, sortOrder, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * 주문 상태 변경 (관리자용)
     */
    @PatchMapping("/{orderId}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody OrderStatusUpdateRequest request) {
        OrderResponse response = adminOrderService.updateOrderStatus(orderId, request.getStatus());
        return ResponseEntity.ok(response);
    }

    /**
     * 배송 상태 변경 (관리자용)
     */
    @PatchMapping("/{orderId}/delivery-status")
    public ResponseEntity<OrderResponse> updateDeliveryStatus(
            @PathVariable Long orderId,
            @RequestBody DeliveryStatusUpdateRequest request) {
        OrderResponse response = adminOrderService.updateDeliveryStatus(orderId, request.getDeliveryStatus());
        return ResponseEntity.ok(response);
    }
}
