package com.pawbridge.paymentservice.client;

import com.pawbridge.paymentservice.domain.payment.dto.StoreOrderResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "store-service", url = "${service.store.url}")
public interface StoreServiceClient {

    @GetMapping("/api/orders/uuid/{orderUuid}")
    StoreOrderResponse getOrder(@PathVariable("orderUuid") String orderUuid);
}
