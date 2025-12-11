 package com.pawbridge.storeservice.domain.order.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final OrderRepository orderRepository;
    private final com.pawbridge.storeservice.domain.order.service.OrderService orderService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    @KafkaListener(topics = {"payment.events", "payment"}, groupId = "payment-group", containerFactory = "kafkaListenerContainerFactory")
    public void handlePaymentEvents(String message) {
        log.info("Received Payment Event: {}", message);
        try {
            JsonNode root = objectMapper.readTree(message);
            
            // 1. Debezium 'payload' Field Extraction (if wrapped)
            // If the SMT is configured to unwrap, 'payload' might be the root.
            // If 'payload' field contains a String (escaped JSON), parse it again.
            if (root.has("payload") && root.get("payload").isTextual()) {
                String payloadString = root.get("payload").asText();
                root = objectMapper.readTree(payloadString);
            } else if (root.has("payload") && root.get("payload").isObject()) {
                root = root.get("payload");
            }
            
            // 2. Extract Fields (TossPaymentResponse structure)
            if (!root.has("orderId") || !root.has("status")) {
                log.warn("Invalid Payment Event Format (missing orderId or status): {}", message);
                return;
            }

            String orderId = root.path("orderId").asText();
            String status = root.path("status").asText();

            log.info("Processing Payment Event - OrderID: {}, Status: {}", orderId, status);

            if ("DONE".equals(status)) {
                completeOrder(orderId);
            } else if ("ABORTED".equals(status) || "CANCELED".equals(status)) {
                log.warn("Payment Failed/Canceled for Order: {}. Triggering Rollback...", orderId);
                orderService.cancelOrder(orderId);
            } else {
                log.info("Skipping payment event with status: {}", status);
            }

        } catch (JsonProcessingException e) {
            log.error("Payment Event Parsing Failed", e);
        } catch (Exception e) {
            log.error("Payment Event Processing Failed", e);
        }
    }

    private void completeOrder(String orderUuid) {
        Order order = orderRepository.findByOrderUuid(orderUuid)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderUuid));

        if (order.getStatus() == com.pawbridge.storeservice.domain.order.entity.OrderStatus.PAID) {
            log.info("Order {} is already paid.", orderUuid);
            return;
        }

        order.paid();
        log.info("Order {} paid successfully.", orderUuid);

        // Update Ranking in Redis
        updateRanking(order);
    }

    private void updateRanking(Order order) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE); // yyyyMMdd
        String rankingKey = "store:ranking:daily:" + today;

        order.getOrderItems().forEach(item -> {
            // Increment score by quantity
            // Member: ProductID (Not SKU ID, usually we rank by Product)
            Long productId = item.getProductSKU().getProduct().getId();
            redisTemplate.opsForZSet().incrementScore(rankingKey, String.valueOf(productId), item.getQuantity());
        });
    }
}
