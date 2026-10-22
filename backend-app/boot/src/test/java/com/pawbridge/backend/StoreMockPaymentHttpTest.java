package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController;
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(properties="pawbridge.store-mock-payment.enabled=TrUe",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={MockOrderPaymentController.class, MockOrderPaymentExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class StoreMockPaymentHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean StorePaymentEvents payments;

    @ParameterizedTest
    @ValueSource(strings={"ROLE_USER","ROLE_ADMIN","ROLE_SHELTER"})
    void given__any_verified_role__when__paying_any_order_id__then__run_the_original_business_without_owner_check(String role) throws Exception {
        var result=mvc.perform(command("POST","/api/v1/orders/41/payment").header("Authorization","Bearer "+F3TestInputs.token(7,role)))
                .andExpect(status().isOk()).andReturn();
        verify(payments).processPayment(41L);
        verifyNoMoreInteractions(payments);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    void given__only_a_forged_identity_header__when__paying__then__reject_before_business() throws Exception {
        mvc.perform(command("POST","/api/v1/orders/41/payment")).andExpect(status().isUnauthorized());
        verifyNoInteractions(payments);
    }

    @Test
    void given__an_invalid_bearer__when__paying__then__reject_before_business() throws Exception {
        mvc.perform(command("POST","/api/v1/orders/41/payment").header("Authorization","Bearer synthetic-invalid-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(payments);
    }

    @Test
    void given__a_missing_order__when__paying__then__map_the_original_illegal_argument_to_c001() throws Exception {
        doThrow(new IllegalArgumentException("Order not found: 41")).when(payments).processPayment(41L);
        var json=mapper.readTree(mvc.perform(verified("POST","/api/v1/orders/41/payment"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsByteArray());
        assertThat(json.path("message").asText()).isEqualTo("Invalid Input Value");
        assertThat(json.path("code").asText()).isEqualTo("C001");
        assertThat(json.path("errors").isArray()).isTrue();
        assertThat(json.path("errors")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"state","runtime"})
    void given__a_non_pending_order_or_event_failure__when__paying__then__map_to_the_original_server_error(String failure) throws Exception {
        doThrow(failure.equals("state")?new IllegalStateException("Order is not in PENDING state. Current: PAID")
                :new RuntimeException("Failed to publish payment event")).when(payments).processPayment(41L);
        var json=mapper.readTree(mvc.perform(verified("POST","/api/v1/orders/41/payment"))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsByteArray());
        assertThat(json.path("message").asText()).isEqualTo("Server Error");
        assertThat(json.path("code").asText()).isEqualTo("C004");
        assertThat(json.toString()).doesNotContain("PENDING").doesNotContain("publish");
    }

    @Test
    void given__a_non_numeric_order_id__when__paying__then__return_the_original_type_error_before_business() throws Exception {
        var json=mapper.readTree(mvc.perform(verified("POST","/api/v1/orders/abc/payment"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsByteArray());
        assertThat(json.path("message").asText()).isEqualTo("Invalid Type Value");
        assertThat(json.path("code").asText()).isEqualTo("C005");
        assertThat(json.path("errors").get(0).path("field").asText()).isEqualTo("orderId");
        assertThat(json.path("errors").get(0).path("value").asText()).isEqualTo("abc");
        assertThat(json.path("errors").get(0).path("reason").asText()).isEqualTo("typeMismatch");
        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/orders/41/payment","PUT,/api/v1/orders/41/payment","PATCH,/api/v1/orders/41/payment",
            "DELETE,/api/v1/orders/41/payment","POST,/api/orders/41/payment","POST,/api/v1/orders/41/payment/extra",
            "POST,/api/v1/orders/payment","POST,/api/v1/orders/41/payments"})
    void given__a_verified_member__when__using_an_unregistered_method_or_alias__then__deny_before_business(String method,String path) throws Exception {
        mvc.perform(verified(method,path)).andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }

    static MockHttpServletRequestBuilder command(String method,String path) {
        return request(HttpMethod.valueOf(method),path).header("X-User-Id","99");
    }

    static MockHttpServletRequestBuilder verified(String method,String path) {
        return command(method,path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER"));
    }
}

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={MockOrderPaymentController.class, MockOrderPaymentExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class StoreMockPaymentDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean StorePaymentEvents payments;

    @Test
    void given__no_mock_payment_flag__when__a_verified_member_pays__then__deny_before_business() throws Exception {
        mvc.perform(StoreMockPaymentHttpTest.verified("POST","/api/v1/orders/41/payment")).andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }
}

@WebMvcTest(properties={"pawbridge.store-mock-payment.enabled=on","pawbridge.payments.enabled=true","pawbridge.store-payment-events.enabled=true"},
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={MockOrderPaymentController.class, MockOrderPaymentExceptionHandler.class,
        ReadSecurityConfiguration.class, F3TestInputs.SecurityInputs.class})
class StoreMockPaymentAliasDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean StorePaymentEvents payments;

    @Test
    void given__a_noncanonical_flag_and_other_payment_flags__when__a_verified_member_pays__then__deny_before_business() throws Exception {
        mvc.perform(StoreMockPaymentHttpTest.verified("POST","/api/v1/orders/41/payment")).andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }
}
