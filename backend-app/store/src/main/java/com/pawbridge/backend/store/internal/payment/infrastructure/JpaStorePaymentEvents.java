package com.pawbridge.backend.store.internal.payment.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.model.Outbox;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OrderRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.OutboxRepository;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderItem;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionOperations;

/** Original consumer/order branches, not a stronger payment policy. No pool, EMF, Kafka or Redis client is owned here. */
public final class JpaStorePaymentEvents implements StorePaymentEvents {
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final TransactionOperations writes;
    private final StockRestorer stocks;
    private final Ranking ranking;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JpaStorePaymentEvents(EntityManager sharedWriter, TransactionOperations writes, StockRestorer stocks,
            Ranking ranking, ObjectMapper mapper, Clock clock) {
        this(new JpaRepositoryFactory(sharedWriter).getRepository(OrderRepository.class),
                new JpaRepositoryFactory(sharedWriter).getRepository(OutboxRepository.class), writes, stocks, ranking, mapper, clock);
    }

    JpaStorePaymentEvents(OrderRepository orders, OutboxRepository outbox, TransactionOperations writes,
            StockRestorer stocks, Ranking ranking, ObjectMapper mapper, Clock clock) {
        this.orders=orders; this.outbox=outbox; this.writes=writes;
        this.stocks=stocks; this.ranking=ranking; this.mapper=mapper; this.clock=clock;
    }

    @Override public void handle(String event) {
        writes.executeWithoutResult(transaction -> {
            try {
                JsonNode root=mapper.readTree(event);
                if(root!=null && root.has("payload") && root.get("payload").isTextual()) {
                    root=mapper.readTree(root.get("payload").asText());
                } else if(root!=null && root.has("payload") && root.get("payload").isObject()) {
                    root=root.get("payload");
                }
                if(root==null || !root.path("orderId").isTextual() || root.path("orderId").asText().isBlank()
                        || !root.path("status").isTextual() || root.path("status").asText().isBlank()) {
                    throw new IllegalArgumentException("Payment event requires orderId and status");
                }
                String orderId=root.path("orderId").asText();
                String status=root.path("status").asText();
                if("DONE".equals(status)) completeOrder(orderId);
                else if("ABORTED".equals(status) || "CANCELED".equals(status)) cancelWithinTransaction(orderId);
            } catch(JsonProcessingException failure) {
                throw new IllegalArgumentException("Payment Event Parsing Failed",failure);
            }
        });
    }

    @Override public void processPayment(long orderId) {
        writes.executeWithoutResult(transaction -> {
            Order order=orders.findById(orderId).orElseThrow(()->new IllegalArgumentException("Order not found: "+orderId));
            if(order.getStatus()!=OrderStatus.PENDING)
                throw new IllegalStateException("Order is not in PENDING state. Current: "+order.getStatus());
            order.paid();
            try {
                Map<String,Object> payload=new HashMap<>();
                payload.put("orderId",order.getId());payload.put("userId",order.getUserId());
                payload.put("totalAmount",order.getTotalAmount());payload.put("status","PAID");
                outbox.save(Outbox.builder().aggregateType("order").aggregateId(String.valueOf(order.getId()))
                        .eventType("ORDER_PAID").payload(mapper.writeValueAsString(payload)).build());
            } catch(JsonProcessingException failure) {
                throw new RuntimeException("Failed to publish payment event",failure);
            }
        });
    }

    @Override public void cancelOrder(String orderUuid) {
        writes.executeWithoutResult(transaction -> cancelWithinTransaction(orderUuid));
    }

    private void completeOrder(String orderUuid) {
        Order order=byUuid(orderUuid);
        if(order.getStatus()==OrderStatus.PAID) return;
        order.paid();
        String key="store:ranking:daily:"+LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        order.getOrderItems().forEach(item -> ranking.increment(key,
                String.valueOf(item.getProductSKU().getProduct().getId()),item.getQuantity()));
    }

    private void cancelWithinTransaction(String orderUuid) {
        Order order=byUuid(orderUuid);
        if(order.getStatus()==OrderStatus.CANCELLED) return;
        order.cancelOrder();
        Map<Long,Integer> quantities=order.getOrderItems().stream().collect(Collectors.toMap(
                item -> item.getProductSKU().getId(),OrderItem::getQuantity,Integer::sum,LinkedHashMap::new));
        stocks.increaseStocks(quantities);
    }

    private Order byUuid(String uuid) {
        return orders.findByOrderUuid(uuid).orElseThrow(()->new IllegalArgumentException("Order not found: "+uuid));
    }
}
