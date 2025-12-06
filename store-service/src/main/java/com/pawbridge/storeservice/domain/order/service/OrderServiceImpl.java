package com.pawbridge.storeservice.domain.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.common.entity.Outbox;
import com.pawbridge.storeservice.common.repository.OutboxRepository;
import com.pawbridge.storeservice.domain.order.dto.OrderCreateRequest;
import com.pawbridge.storeservice.domain.order.dto.OrderResponse;
import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.entity.OrderItem;
import com.pawbridge.storeservice.domain.order.repository.OrderRepository;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductSKURepository productSKURepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
        String orderUuid = UUID.randomUUID().toString();
        
        // 1. Create Order
        Order order = Order.builder()
                .orderUuid(orderUuid)
                .userId(userId)
                .deliveryAddress(request.getDeliveryAddress())
                .totalAmount(0L)
                .build();

        long totalAmount = 0L;

        // 2. Process Items with Pessimistic Lock
        for (OrderCreateRequest.OrderItemDto itemDto : request.getItems()) {
            ProductSKU sku = productSKURepository.findByIdWithLock(itemDto.getSkuId())
                    .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + itemDto.getSkuId()));

            // Decrease Stock
            sku.decreaseStock(itemDto.getQuantity());

            // Create OrderItem
            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .productSKU(sku)
                    .productName(sku.getProduct().getName())
                    .skuCode(sku.getSkuCode())
                    .price(sku.getPrice())
                    .quantity(itemDto.getQuantity())
                    .build();
            
            order.getOrderItems().add(orderItem);
            totalAmount += sku.getPrice() * itemDto.getQuantity();
        }

        // 3. Finalize Order
        order.updateTotalAmount(totalAmount);
        Order savedOrder = orderRepository.save(order);
        
        OrderResponse response = OrderResponse.from(savedOrder);

        // 4. Save Outbox Event (ORDER_CREATED)
        try {
            String payload = objectMapper.writeValueAsString(response);
            Outbox outbox = Outbox.builder()
                    .aggregateType("ORDER")
                    .aggregateId(orderUuid)
                    .eventType("ORDER_CREATED")
                    .payload(payload)
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to create outbox event for order", e);
            throw new RuntimeException("Order created but failed to save event", e);
        }

        return response;
    }
}