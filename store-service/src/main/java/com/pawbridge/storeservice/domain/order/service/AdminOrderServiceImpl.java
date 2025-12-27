package com.pawbridge.storeservice.domain.order.service;

import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.entity.DeliveryStatus;
import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.entity.OrderStatus;
import com.pawbridge.storeservice.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자용 주문 관리 서비스 구현체
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminOrderServiceImpl implements AdminOrderService {

    private final OrderRepository orderRepository;

    @Override
    public Page<OrderResponse> getAllOrders(
            OrderStatus status, 
            DeliveryStatus deliveryStatus, 
            Long userId,
            String keyword,
            String sortBy,
            String sortOrder,
            Pageable pageable) {
        
        Specification<Order> spec = Specification.where(null);
        
        // 상태 필터
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        
        // 배송 상태 필터
        if (deliveryStatus != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("deliveryStatus"), deliveryStatus));
        }
        
        // 사용자 ID 필터
        if (userId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userId"), userId));
        }
        
        // 키워드 검색 (주문번호 또는 수령인 이름)
        if (keyword != null && !keyword.isBlank()) {
            String likePattern = "%" + keyword.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("orderUuid")), likePattern),
                    cb.like(cb.lower(root.get("receiverName")), likePattern)
            ));
        }
        
        // 정렬 적용
        Sort sort = Sort.by(
                "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC,
                sortBy
        );
        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        
        Page<Order> orders = orderRepository.findAll(spec, sortedPageable);
        return orders.map(OrderResponse::from);
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다. orderId: " + orderId));
        
        log.info("주문 상태 변경 - orderId: {}, {} -> {}", orderId, order.getStatus(), status);
        order.updateStatus(status);
        
        return OrderResponse.from(order);
    }

    @Override
    @Transactional
    public OrderResponse updateDeliveryStatus(Long orderId, DeliveryStatus deliveryStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다. orderId: " + orderId));
        
        log.info("배송 상태 변경 - orderId: {}, {} -> {}", orderId, order.getDeliveryStatus(), deliveryStatus);
        order.updateDeliveryStatus(deliveryStatus);
        
        return OrderResponse.from(order);
    }
}
