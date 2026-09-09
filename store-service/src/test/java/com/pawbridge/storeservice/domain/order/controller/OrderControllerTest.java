package com.pawbridge.storeservice.domain.order.controller;

import com.pawbridge.storeservice.common.exception.GlobalExceptionHandler;
import com.pawbridge.storeservice.domain.order.dto.DirectOrderCreateRequest;
import com.pawbridge.storeservice.domain.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderControllerTest {
    private OrderService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OrderService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({"skuId, omitted", "skuId, null", "skuId, 0", "skuId, -1",
            "quantity, omitted", "quantity, null", "quantity, 0", "quantity, -1"})
    void givenInvalidSkuOrQuantity_whenDirectOrderRequested_thenRejectsBeforeService(String field, String value)
            throws Exception {
        String validField = field.equals("skuId") ? "\"quantity\":1" : "\"skuId\":7";
        String invalidField = value.equals("omitted") ? "" : ",\"" + field + "\":" + value;
        mvc.perform(post("/api/v1/orders/direct").header("X-User-Id", 10)
                        .contentType(MediaType.APPLICATION_JSON).content("{" + validField + invalidField + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field));
        verifyNoInteractions(service);
    }

    @Test
    void givenValidDirectOrder_whenRequested_thenPassesSkuAndQuantityToService() throws Exception {
        mvc.perform(post("/api/v1/orders/direct").header("X-User-Id", 10)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":7,"quantity":2,"receiverName":"Test buyer",
                                 "receiverPhone":"01000000000","deliveryAddress":"Test address"}
                                """))
                .andExpect(status().isCreated());
        ArgumentCaptor<DirectOrderCreateRequest> request = ArgumentCaptor.forClass(DirectOrderCreateRequest.class);
        verify(service).createDirectOrder(eq(10L), request.capture());
        assertEquals(7L, request.getValue().getSkuId());
        assertEquals(2, request.getValue().getQuantity());
        assertEquals("Test address", request.getValue().getDeliveryAddress());
    }
}
