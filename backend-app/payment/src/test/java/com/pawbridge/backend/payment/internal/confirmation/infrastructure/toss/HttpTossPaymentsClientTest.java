package com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** In-memory HTTP expectations; no network or provider call. */
class HttpTossPaymentsClientTest {
    @Test void given__a_synthetic_confirmation__when__sending__then__preserve_original_post_path_basic_header_and_body() {
        var builder=RestClient.builder().baseUrl("https://provider.invalid/v1");
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new HttpTossPaymentsClient(builder.build());
        server.expect(requestTo("https://provider.invalid/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization","Basic synthetic-only"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"paymentKey\":\"synthetic-key\",\"orderId\":\"order-uuid\",\"amount\":200}"))
                .andRespond(withSuccess("{\"paymentKey\":\"synthetic-key\",\"orderId\":\"order-uuid\",\"status\":\"READY\"}",MediaType.APPLICATION_JSON));
        var result=client.confirmPayment("Basic synthetic-only",new TossPaymentConfirmRequest("synthetic-key","order-uuid",200L));
        assertThat(result.getStatus()).isEqualTo("READY");assertThat(result.getOrderId()).isEqualTo("order-uuid");
        server.verify();
    }

    @Test void given__an_internal_compensation__when__sending__then__preserve_original_cancel_path_and_reason_body() {
        var builder=RestClient.builder().baseUrl("https://provider.invalid/v1");
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new HttpTossPaymentsClient(builder.build());
        server.expect(requestTo("https://provider.invalid/v1/payments/synthetic-key/cancel"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization","Basic synthetic-only"))
                .andExpect(content().json("{\"cancelReason\":\"synthetic reason\"}"))
                .andRespond(withSuccess("{\"status\":\"CANCELED\"}",MediaType.APPLICATION_JSON));
        assertThat(client.cancelPayment("Basic synthetic-only","synthetic-key",new TossPaymentCancelRequest("synthetic reason")).getStatus()).isEqualTo("CANCELED");
        server.verify();
    }

    @Test void given__an_unknown_confirmation_result__when__looking_up__then__get_the_payment_by_key_with_the_same_basic_header() {
        var builder=RestClient.builder().baseUrl("https://provider.invalid/v1");
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new HttpTossPaymentsClient(builder.build());
        server.expect(requestTo("https://provider.invalid/v1/payments/synthetic-key"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization","Basic synthetic-only"))
                .andRespond(withSuccess("{\"paymentKey\":\"synthetic-key\",\"orderId\":\"order-uuid\",\"status\":\"DONE\",\"totalAmount\":200}",MediaType.APPLICATION_JSON));
        var result=client.getPayment("Basic synthetic-only","synthetic-key");
        assertThat(result.getStatus()).isEqualTo("DONE");assertThat(result.getTotalAmount()).isEqualTo(200L);
        server.verify();
    }

    @Test void given__a_provider_http_failure__when__sending__then__propagate_to_the_original_business_error_branch() {
        var builder=RestClient.builder().baseUrl("https://provider.invalid/v1");
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new HttpTossPaymentsClient(builder.build());
        server.expect(requestTo("https://provider.invalid/v1/payments/confirm"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client.confirmPayment("Basic synthetic-only",new TossPaymentConfirmRequest("synthetic-key","order-uuid",200L)))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        server.verify();
    }
}
