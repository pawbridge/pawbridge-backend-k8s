package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.payment.internal.confirmation.application.Payments;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse;
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentController;
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentInputExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(properties="pawbridge.payments.enabled=true",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={PaymentController.class, PaymentInputExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class PaymentConfirmationHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean Payments payments;

    @Test
    void given__a_verified_member_and_forged_header__when__confirming__then__use_the_jwt_identity_and_bare_toss_response() throws Exception {
        when(payments.confirmPayment(eq(7L),any())).thenReturn(TossPaymentResponse.builder()
                .paymentKey("synthetic-payment-key").orderId("legacy-order-id")
                .currency("KRW").method("카드").totalAmount(200L).balanceAmount(200L).status("DONE").build());

        var result=mvc.perform(verified("POST","/api/payments/confirm")
                .content("{\"paymentKey\":\"synthetic-payment-key\",\"orderId\":\"legacy-order-id\",\"amount\":200}"))
                .andExpect(status().isOk()).andReturn();

        var input=ArgumentCaptor.forClass(TossPaymentConfirmRequest.class);
        verify(payments).confirmPayment(eq(7L),input.capture());
        assertThat(input.getValue().getPaymentKey()).isEqualTo("synthetic-payment-key");
        assertThat(input.getValue().getOrderId()).isEqualTo("legacy-order-id");
        assertThat(input.getValue().getAmount()).isEqualTo(200L);
        verifyNoMoreInteractions(payments);
        var json=mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.path("paymentKey").asText()).isEqualTo("synthetic-payment-key");
        assertThat(json.path("orderId").asText()).isEqualTo("legacy-order-id");
        assertThat(json.path("currency").asText()).isEqualTo("KRW");
        assertThat(json.path("method").asText()).isEqualTo("카드");
        assertThat(json.path("totalAmount").asLong()).isEqualTo(200L);
        assertThat(json.path("balanceAmount").asLong()).isEqualTo(200L);
        assertThat(json.path("status").asText()).isEqualTo("DONE");
        assertThat(json.has("data")).isFalse();
        assertThat(json.has("code")).isFalse();
    }

    @Test
    void given__omitted_nullable_request_fields__when__confirming__then__preserve_the_original_dto_without_new_constraints() throws Exception {
        when(payments.confirmPayment(eq(7L),any())).thenReturn(TossPaymentResponse.builder().status("ABORTED").build());
        var result=mvc.perform(verified("POST","/api/payments/confirm").content("{}"))
                .andExpect(status().isOk()).andReturn();
        var input=ArgumentCaptor.forClass(TossPaymentConfirmRequest.class);
        verify(payments).confirmPayment(eq(7L),input.capture());
        assertThat(input.getValue().getPaymentKey()).isNull();
        assertThat(input.getValue().getOrderId()).isNull();
        assertThat(input.getValue().getAmount()).isNull();
        verifyNoMoreInteractions(payments);
        var json=mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.path("status").asText()).isEqualTo("ABORTED");
        assertThat(json.has("data")).isFalse();
        assertThat(json.has("code")).isFalse();
        assertThat(json.has("failure")).isTrue();
        assertThat(json.get("failure").isNull()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"foreign,403,본인의 주문만 결제할 수 있습니다.","not-pending,409,결제 대기 상태의 주문만 결제할 수 있습니다."})
    void given__a_pre_provider_order_rejection__when__confirming__then__map_it_to_a_scoped_problem_response(String kind,int status,String detail) throws Exception {
        when(payments.confirmPayment(eq(7L),any())).thenThrow(kind.equals("foreign")
                ? new com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderAccessDeniedException()
                : new com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderNotPayableException());
        var result=mvc.perform(verified("POST","/api/payments/confirm")
                .content("{\"paymentKey\":\"synthetic-payment-key\",\"orderId\":\"legacy-order-id\",\"amount\":200}"))
                .andExpect(status().is(status)).andReturn();
        var json=mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.path("status").asInt()).isEqualTo(status);
        assertThat(json.path("detail").asText()).isEqualTo(detail);
    }

    @Test
    void given__only_a_forged_identity_header__when__confirming__then__reject_before_the_payment_application() throws Exception {
        mvc.perform(command("POST","/api/payments/confirm").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(payments);
    }

    @Test
    void given__an_invalid_bearer_and_forged_header__when__confirming__then__reject_before_the_payment_application() throws Exception {
        mvc.perform(command("POST","/api/payments/confirm").header("Authorization","Bearer synthetic-invalid-token").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @ValueSource(strings={"paymentKey","orderId"})
    void given__a_non_bmp_request_field__when__a_verified_member_confirms__then__use_the_existing_scoped_validation_response(String field) throws Exception {
        var body=mapper.createObjectNode().put("paymentKey","synthetic-payment-key")
                .put("orderId","legacy-order-id").put("amount",200);
        body.put(field,"\uD83D\uDE00");
        var result=mvc.perform(verified("POST","/api/payments/confirm").content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest()).andReturn();
        var json=mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.path("detail").asText()).isEqualTo("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.");
        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/payments/confirm","PUT,/api/payments/confirm","PATCH,/api/payments/confirm",
            "DELETE,/api/payments/confirm","POST,/api/v1/payments/confirm","POST,/api/payments",
            "POST,/api/payments/confirm/extra"})
    void given__a_verified_member__when__using_an_unregistered_method_or_alias__then__deny_before_business(String method,String path) throws Exception {
        mvc.perform(verified(method,path).content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }

    static MockHttpServletRequestBuilder command(String method,String path) {
        return request(HttpMethod.valueOf(method),path).header("X-User-Id","99").contentType(MediaType.APPLICATION_JSON);
    }

    static MockHttpServletRequestBuilder verified(String method,String path) {
        return command(method,path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER"));
    }
}

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={PaymentController.class, PaymentInputExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class PaymentConfirmationDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean Payments payments;

    @Test
    void given__no_payment_flag__when__a_verified_member_confirms__then__deny_before_business() throws Exception {
        mvc.perform(PaymentConfirmationHttpTest.verified("POST","/api/payments/confirm").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }
}

@WebMvcTest(properties="pawbridge.payments.enabled=on",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={PaymentController.class, PaymentInputExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class PaymentConfirmationAliasDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean Payments payments;

    @Test
    void given__a_noncanonical_payment_flag__when__a_verified_member_confirms__then__deny_before_business() throws Exception {
        mvc.perform(PaymentConfirmationHttpTest.verified("POST","/api/payments/confirm").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }
}
