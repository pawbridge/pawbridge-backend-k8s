package com.pawbridge.storeservice.domain.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.entity.OrderStatus;
import com.pawbridge.storeservice.domain.order.repository.OrderRepository;
import com.pawbridge.storeservice.domain.order.service.OrderService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentEventConsumerTest {
    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderService service = mock(OrderService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PaymentEventConsumer consumer = new PaymentEventConsumer(repository, service, new ObjectMapper(), redis);

    @Test
    void completed_payment_updates_order() {
        Order order = Order.builder().orderUuid("order-1").build();
        when(repository.findByOrderUuid("order-1")).thenReturn(Optional.of(order));
        consumer.handlePaymentEvents("{\"orderId\":\"order-1\",\"status\":\"DONE\"}");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(redis);
    }

    @Test
    void already_paid_order_does_not_repeat_ranking() {
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.PAID);
        when(repository.findByOrderUuid("order-1")).thenReturn(Optional.of(order));
        consumer.handlePaymentEvents("{\"orderId\":\"order-1\",\"status\":\"DONE\"}");
        verify(order, never()).paid();
        verify(order, never()).getOrderItems();
        verifyNoInteractions(redis);
    }

    @Test
    void storage_failure_propagates_instead_of_becoming_success() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(repository.findByOrderUuid("order-1")).thenThrow(failure);
        assertThatThrownBy(() -> consumer.handlePaymentEvents("{\"orderId\":\"order-1\",\"status\":\"DONE\"}"))
                .isSameAs(failure);
    }

    @Test
    void cancellation_failure_propagates_then_retry_succeeds() {
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(service).cancelOrder("order-1");
        String event = "{\"orderId\":\"order-1\",\"status\":\"CANCELED\"}";
        assertThatThrownBy(() -> consumer.handlePaymentEvents(event)).isInstanceOf(IllegalStateException.class);
        consumer.handlePaymentEvents(event);
        verify(service, times(2)).cancelOrder("order-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{}", "{\"orderId\":null,\"status\":\"DONE\"}",
            "{\"orderId\":\" \",\"status\":\"DONE\"}", "{\"orderId\":42,\"status\":\"DONE\"}"})
    void invalid_event_propagates_without_business_side_effects(String event) {
        assertThatThrownBy(() -> consumer.handlePaymentEvents(event)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, service, redis);
    }
}
