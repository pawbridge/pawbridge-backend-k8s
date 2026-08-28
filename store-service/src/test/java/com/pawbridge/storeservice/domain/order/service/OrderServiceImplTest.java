package com.pawbridge.storeservice.domain.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.common.entity.Outbox;
import com.pawbridge.storeservice.common.repository.OutboxRepository;
import com.pawbridge.storeservice.domain.cart.service.CartService;
import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.repository.OrderRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import com.pawbridge.storeservice.domain.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CartService cartService;
    @Mock
    private ProductService productService;
    @Mock
    private ProductSKURepository productSKURepository;
    @Mock
    private OutboxRepository outboxRepository;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(
                orderRepository,
                cartService,
                productService,
                productSKURepository,
                outboxRepository,
                new ObjectMapper()
        );
    }

    @Test
    void givenPendingOrder_whenPaymentProcessed_thenPublishesLowercaseOrderAggregate() {
        Order order = Order.builder()
                .orderUuid("order-uuid")
                .userId(1L)
                .totalAmount(10_000L)
                .deliveryAddress("서울")
                .receiverName("구매자")
                .receiverPhone("010-0000-0000")
                .build();
        ReflectionTestUtils.setField(order, "id", 10L);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        orderService.processPayment(10L);

        ArgumentCaptor<Outbox> outboxCaptor = ArgumentCaptor.forClass(Outbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertEquals("order", outboxCaptor.getValue().getAggregateType());
        assertEquals("10", outboxCaptor.getValue().getAggregateId());
    }
}
