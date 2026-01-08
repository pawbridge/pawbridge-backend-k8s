package com.pawbridge.storeservice.domain.order.service;

import com.pawbridge.storeservice.domain.cart.dto.CartItemResponse;
import com.pawbridge.storeservice.domain.cart.service.CartService;
import com.pawbridge.storeservice.domain.order.dto.DirectOrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.entity.OrderItem;
import com.pawbridge.storeservice.domain.order.entity.OrderStatus;
import com.pawbridge.storeservice.domain.order.repository.OrderRepository;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import com.pawbridge.storeservice.domain.product.service.ProductService;
import com.pawbridge.storeservice.common.entity.Outbox;
import com.pawbridge.storeservice.common.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final ProductService productService;
    private final ProductSKURepository productSKURepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
        // 1. 장바구니 조회
        List<CartItemResponse> cartItems = cartService.getMyCart(userId);
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }

        // 2. Deadlock 방지를 위한 SKU ID 순 정렬 (비관적 락 획득 순서 보장)
        cartItems.sort(Comparator.comparing(CartItemResponse::getSkuId));

        long totalAmount = 0;

        // 3. 재고 차감 (ProductService 내부에서 비관적 락 및 상태 검증 처리)
        for (CartItemResponse item : cartItems) {
            productService.decreaseStock(item.getSkuId(), item.getQuantity());
            totalAmount += item.getPrice() * item.getQuantity();
        }

        // 4. 주문 엔티티 생성
        Order order = Order.builder()
                .userId(userId)
                .orderUuid(UUID.randomUUID().toString())
                .totalAmount(totalAmount)
                .deliveryAddress(request.getDeliveryAddress())
                .receiverName(request.getReceiverName())
                .receiverPhone(request.getReceiverPhone())
                .deliveryMessage(request.getDeliveryMessage())
                .build();

        // 5. 주문 상품 생성
        List<Long> skuIds = cartItems.stream().map(CartItemResponse::getSkuId).collect(Collectors.toList());
        List<ProductSKU> skus = productSKURepository.findAllById(skuIds);
        var skuMap = skus.stream().collect(Collectors.toMap(ProductSKU::getId, sku -> sku));

        for (CartItemResponse item : cartItems) {
            ProductSKU sku = skuMap.get(item.getSkuId());
            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .productSKU(sku)
                    .productName(sku.getProduct().getName())
                    .skuCode(sku.getSkuCode())
                    .price(sku.getPrice())
                    .quantity(item.getQuantity())
                    .build();
            order.getOrderItems().add(orderItem);
        }

        // 6. 주문 저장
        orderRepository.save(order);

        // 7. 장바구니 비우기
        cartService.clearCart(userId);

        log.info("Order created successfully. OrderId: {}, UserId: {}", order.getId(), userId);

        return OrderResponse.from(order);
    }

    @Override
    @Transactional
    public OrderResponse createDirectOrder(Long userId, DirectOrderCreateRequest request) {
        Long skuId = request.getSkuId();
        Integer quantity = request.getQuantity();

        // 1. 재고 차감 (ProductService 내부에서 비관적 락 및 상태 검증 처리)
        productService.decreaseStock(skuId, quantity);

        // 2. 주문 정보 생성을 위해 SKU 조회 (이미 락이 해제된 상태이므로 일반 조회)
        ProductSKU sku = productSKURepository.findById(skuId)
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + skuId));
        
        long totalAmount = sku.getPrice() * quantity;

        // 3. 주문 엔티티 생성
        Order order = Order.builder()
                .userId(userId)
                .orderUuid(UUID.randomUUID().toString())
                .totalAmount(totalAmount)
                .deliveryAddress(request.getDeliveryAddress())
                .receiverName(request.getReceiverName())
                .receiverPhone(request.getReceiverPhone())
                .deliveryMessage(request.getDeliveryMessage())
                .build();

        // 4. 주문 상품 생성
        OrderItem orderItem = OrderItem.builder()
                .order(order)
                .productSKU(sku)
                .productName(sku.getProduct().getName())
                .skuCode(sku.getSkuCode())
                .price(sku.getPrice())
                .quantity(quantity)
                .build();
        
        order.getOrderItems().add(orderItem);

        // 5. 주문 저장
        orderRepository.save(order);

        log.info("Direct Order created. OrderId: {}, UserId: {}", order.getId(), userId);

        return OrderResponse.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderByUuid(String orderUuid) {
        Order order = orderRepository.findByOrderUuid(orderUuid)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderUuid));
        return OrderResponse.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        
        // 본인 주문인지 검증
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("해당 주문에 대한 접근 권한이 없습니다.");
        }
        
        return OrderResponse.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersByUserId(Long userId, OrderStatus status, Pageable pageable) {
        Page<Order> orders;
        if (status != null) {
            orders = orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
        } else {
            orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }
        return orders.map(OrderResponse::from);
    }

    @Override
    @Transactional
    public void processPayment(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        
        // Validation
        if (order.getStatus() != OrderStatus.PENDING) {
             throw new IllegalStateException("Order is not in PENDING state. Current: " + order.getStatus());
        }

        order.paid();

        // Outbox Event
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("orderId", order.getId());
            payloadMap.put("userId", order.getUserId());
            payloadMap.put("totalAmount", order.getTotalAmount());
            payloadMap.put("status", "PAID");
            
            String payload = objectMapper.writeValueAsString(payloadMap);
            Outbox outbox = Outbox.builder()
                    .aggregateType("ORDER")
                    .aggregateId(String.valueOf(order.getId()))
                    .eventType("ORDER_PAID")
                    .payload(payload)
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
             log.error("Failed to serialize order event", e);
             throw new RuntimeException("Failed to publish payment event", e);
        }
    }

    @Override
    @Transactional
    public void cancelOrder(String orderUuid) {
        Order order = orderRepository.findByOrderUuid(orderUuid)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderUuid));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            log.warn("Order {} is already canceled.", orderUuid);
            return;
        }

        order.cancelOrder();
        
        // 재고 롤백 (ProductService 내부에서 비관적 락 적용)
        for (OrderItem item : order.getOrderItems()) {
            Long skuId = item.getProductSKU().getId();
            productService.increaseStock(skuId, item.getQuantity());
        }
        
        log.info("Order {} canceled and stock restored.", orderUuid);
    }
}