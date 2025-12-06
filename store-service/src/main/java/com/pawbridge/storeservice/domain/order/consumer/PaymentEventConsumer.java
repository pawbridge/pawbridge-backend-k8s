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
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    @KafkaListener(topics = "payment.events", groupId = "store-service-group")
    public void handlePaymentEvents(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            // [Debezium 연동 시 주의사항]
            // 현재는 Outbox 테이블의 payload(JSON 문자열)가 그대로 들어온다고 가정하고 구현했습니다.
            // 추후 Debezium Connector를 실제로 연결하면, 메시지 구조가 Envelope 형태로 감싸져서 올 수 있습니다.
            // 예: { "payload": { "before": null, "after": { "aggregateId": "...", "payload": "..." } } }
            // 그때는 파싱 로직을 수정해야 합니다. 지금은 핵심 비즈니스 로직(주문 완료 처리)에 집중합니다.
            
            // 메시지에서 orderId와 status 추출 (현재는 TossPaymentResponse JSON 구조 가정)
            String orderId = root.path("orderId").asText();
            String status = root.path("status").asText();

            if ("DONE".equals(status)) {
                completeOrder(orderId);
            }

        } catch (JsonProcessingException e) {
            log.error("결제 이벤트 파싱 실패", e);
        }
    }

    private void completeOrder(String orderUuid) {
        Order order = orderRepository.findByOrderUuid(orderUuid)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderUuid));

        if (order.getStatus().name().equals("COMPLETED")) {
            log.info("Order {} is already completed.", orderUuid);
            return;
        }

        order.completeOrder();
        log.info("Order {} completed successfully.", orderUuid);

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
