package com.pawbridge.storeservice.domain.order.service;

import com.pawbridge.storeservice.domain.cart.dto.CartItemResponse;
import com.pawbridge.storeservice.domain.cart.service.CartService;
import com.pawbridge.storeservice.domain.order.dto.OrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.entity.OrderItem;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final ProductService productService;
    private final ProductSKURepository productSKURepository; // For entity mapping
    private final RedissonClient redissonClient;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    private static final String LOCK_KEY_PREFIX = "stock:sku:";
    private static final long WAIT_TIME = 5L;
    private static final long LEASE_TIME = 3L;

    @Override
    @Transactional
    public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
        // 1. Get Cart Items
        List<CartItemResponse> cartItems = cartService.getMyCart(userId);
        if (cartItems.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }

        // 2. Sort Items by SKU ID to prevent Deadlock (Resource Ordering)
        cartItems.sort(Comparator.comparing(CartItemResponse::getSkuId));

        long totalAmount = 0;
        List<ProductSKU> lockedSkus = new ArrayList<>(); // To track what we processed (implied by execution flow)

        // 3. Process Items (Lock -> Deduct -> Release)
        // Note: In @Transactional, if exception occurs, DB changes rollback.
        // We use Redisson Lock to prevent Race Condition on Stock Read/Write from other threads.
        
        for (CartItemResponse item : cartItems) {
            String lockKey = LOCK_KEY_PREFIX + item.getSkuId();
            RLock lock = redissonClient.getLock(lockKey);

            try {
                boolean available = lock.tryLock(WAIT_TIME, LEASE_TIME, TimeUnit.SECONDS);
                if (!available) {
                    throw new RuntimeException("System is busy. Please try again later. (Lock acquire failed for SKU " + item.getSkuId() + ")");
                }

                // Deduct Stock
                productService.decreaseStock(item.getSkuId(), item.getQuantity());
                
                // Calculate Total
                totalAmount += item.getPrice() * item.getQuantity();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Order processing interrupted", e);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        // 4. Create Order Entity
        Order order = Order.builder()
                .userId(userId)
                .orderUuid(UUID.randomUUID().toString())
                .totalAmount(totalAmount)
                .deliveryAddress(request.getDeliveryAddress())
                .build();

        // 5. Create Order Items
        // Re-fetch SKU entities or use simple references?
        // We need Entity references for OrderItem.
        // Optimization: Batch Fetch SKUs before loop or fetch here?
        // Batch fetch is better, but since we already iterated, let's fetch by IDs.
        List<Long> skuIds = cartItems.stream().map(CartItemResponse::getSkuId).collect(Collectors.toList());
        List<ProductSKU> skus = productSKURepository.findAllById(skuIds);
        
        // Map: ID -> SKU
        var skuMap = skus.stream().collect(Collectors.toMap(ProductSKU::getId, sku -> sku));

        for (CartItemResponse item : cartItems) {
            ProductSKU sku = skuMap.get(item.getSkuId());
            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .productSKU(sku)
                    .productName(sku.getProduct().getName()) // Snapshot
                    .skuCode(sku.getSkuCode()) // Snapshot
                    .price(sku.getPrice()) // Snapshot
                    .quantity(item.getQuantity())
                    .build();
            order.getOrderItems().add(orderItem);
        }

        // 6. Save Order (Cascade saves items)
        orderRepository.save(order);

        // 7. Clear Cart
        cartService.clearCart(userId);

        log.info("Order created successfully. OrderId: {}, UserId: {}", order.getId(), userId);

        return OrderResponse.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        return OrderResponse.from(order);
    }
    @Override
    @Transactional
    public void processPayment(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        
        // Validation
        if (order.getStatus() != com.pawbridge.storeservice.domain.order.entity.OrderStatus.PENDING) {
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
}