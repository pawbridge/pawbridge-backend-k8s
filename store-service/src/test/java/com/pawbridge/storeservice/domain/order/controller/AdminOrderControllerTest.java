package com.pawbridge.storeservice.domain.order.controller;

import com.pawbridge.storeservice.common.exception.GlobalExceptionHandler;
import com.pawbridge.storeservice.domain.order.entity.DeliveryStatus;
import com.pawbridge.storeservice.domain.order.entity.Order;
import com.pawbridge.storeservice.domain.order.entity.OrderItem;
import com.pawbridge.storeservice.domain.order.entity.OrderStatus;
import com.pawbridge.storeservice.domain.order.repository.OrderRepository;
import com.pawbridge.storeservice.domain.order.service.AdminOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminOrderControllerTest {
    private OrderRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminOrderController(new AdminOrderServiceImpl(repository)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void givenAnotherUsersOrder__whenAdminReadsDetail__thenReturnsOrderAndItemSnapshotsWithoutWriting()
            throws Exception {
        Order order = Order.builder()
                .orderUuid("test-order-42").userId(10L).totalAmount(25_000L)
                .receiverName("테스트 수령인").receiverPhone("00000000000")
                .deliveryAddress("테스트 주소").deliveryMessage("테스트 메모").build();
        ReflectionTestUtils.setField(order, "id", 42L);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(2026, 10, 1, 12, 30));
        order.updateStatus(OrderStatus.PAID);
        order.updateDeliveryStatus(DeliveryStatus.SHIPPING);
        // Order details use purchase-time snapshots, not the current product/SKU state.
        order.getOrderItems().add(OrderItem.builder().order(order).productName("구매 당시 상품 A")
                .skuCode("TEST-A").price(10_000L).quantity(2).build());
        order.getOrderItems().add(OrderItem.builder().order(order).productName("구매 당시 상품 B")
                .skuCode("TEST-B").price(5_000L).quantity(1).build());
        when(repository.findById(42L)).thenReturn(Optional.of(order));

        // These identity headers are verified by Gateway; this fixture is not a JWT test.
        mvc.perform(get("/api/v1/admin/orders/42")
                        .header("X-User-Id", 12).header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(42))
                .andExpect(jsonPath("$.orderUuid").value("test-order-42"))
                .andExpect(jsonPath("$.userId").value(10))
                .andExpect(jsonPath("$.totalAmount").value(25_000))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.deliveryStatus").value("SHIPPING"))
                .andExpect(jsonPath("$.receiverName").value("테스트 수령인"))
                .andExpect(jsonPath("$.receiverPhone").value("00000000000"))
                .andExpect(jsonPath("$.deliveryAddress").value("테스트 주소"))
                .andExpect(jsonPath("$.deliveryMessage").value("테스트 메모"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productName").value("구매 당시 상품 A"))
                .andExpect(jsonPath("$.items[0].skuCode").value("TEST-A"))
                .andExpect(jsonPath("$.items[0].price").value(10_000))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[1].productName").value("구매 당시 상품 B"))
                .andExpect(jsonPath("$.items[1].skuCode").value("TEST-B"))
                .andExpect(jsonPath("$.items[1].price").value(5_000))
                .andExpect(jsonPath("$.items[1].quantity").value(1));
        verify(repository).findById(42L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void givenMissingOrder__whenAdminReadsDetail__thenReturnsOrderNotFound() throws Exception {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/admin/orders/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("O001"))
                .andExpect(jsonPath("$.message").value("Order not found"))
                .andExpect(jsonPath("$.errors").isEmpty());
        verify(repository).findById(42L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void givenInvalidOrderId__whenAdminReadsDetail__thenRejectsBeforeRepository() throws Exception {
        mvc.perform(get("/api/v1/admin/orders/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C005"));
        verifyNoInteractions(repository);
    }

    @Test
    void givenRepositoryFailure__whenAdminReadsDetail__thenDoesNotMisreportOrderAsMissing() throws Exception {
        when(repository.findById(42L)).thenThrow(new IllegalStateException("synthetic repository failure"));

        mvc.perform(get("/api/v1/admin/orders/42"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("C004"));
        verify(repository).findById(42L);
        verifyNoMoreInteractions(repository);
    }
}
