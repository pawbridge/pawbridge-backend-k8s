package com.pawbridge.paymentservice.client;

import com.pawbridge.paymentservice.domain.payment.dto.StoreOrderResponse;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoreServiceClientTest {

    @Test
    void getOrderSendsUuidToVersionedStoreEndpoint() throws Exception {
        String orderUuid = "00000000-0000-0000-0000-000000000007";
        Client transport = mock(Client.class);
        StoreOrderResponse expectedOrder = new StoreOrderResponse();
        when(transport.execute(any(Request.class), any(Request.Options.class)))
                .thenAnswer(invocation -> Response.builder()
                        .request(invocation.getArgument(0))
                        .status(200)
                        .reason("OK")
                        .headers(Map.of())
                        .build());
        StoreServiceClient client = Feign.builder()
                .contract(new SpringMvcContract())
                .client(transport)
                .decoder((response, type) -> expectedOrder)
                .target(StoreServiceClient.class, "http://store-service:8083");

        StoreOrderResponse actualOrder = client.getOrder(orderUuid);

        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(transport).execute(request.capture(), any(Request.Options.class));
        assertThat(request.getValue().httpMethod()).isEqualTo(Request.HttpMethod.GET);
        assertThat(request.getValue().url())
                .isEqualTo("http://store-service:8083/api/v1/orders/uuid/" + orderUuid);
        assertThat(actualOrder).isSameAs(expectedOrder);
    }
}
