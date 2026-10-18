package com.pawbridge.paymentservice.persistence;

import com.pawbridge.paymentservice.common.exception.PostgresqlRollbackCharsetAdvice;
import com.pawbridge.paymentservice.domain.payment.controller.PaymentController;
import com.pawbridge.paymentservice.domain.payment.service.PaymentService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentRollbackInputTest {
    private final PaymentService payments = mock(PaymentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PaymentController(payments))
            .setControllerAdvice(new PostgresqlRollbackCharsetAdvice()).build();

    @ParameterizedTest
    @ValueSource(strings={"paymentKey", "orderId"})
    void unsupported_text_is_rejected_before_payment_service_or_provider_calls(String field) throws Exception {
        String key = field.equals("paymentKey") ? "🐕" : "key";
        String order = field.equals("orderId") ? "🐕" : "order";
        String body = String.format("{\"paymentKey\":\"%s\",\"orderId\":\"%s\",\"amount\":1000}", key, order);
        mvc.perform(post("/api/payments/confirm").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")));
        verifyNoInteractions(payments);
    }

    @Test
    void existing_ascii_input_still_reaches_payment_service() throws Exception {
        mvc.perform(post("/api/payments/confirm").header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"key\",\"orderId\":\"order\",\"amount\":1000}"))
                .andExpect(status().isOk());
        verify(payments).confirmPayment(eq(1L), any());
    }
}
