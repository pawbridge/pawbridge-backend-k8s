package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController;
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Default-off original mock payment route; it reuses the explicitly enabled Store payment business and fails fast without it. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.store-mock-payment",name="enabled",havingValue="true")
public class StoreMockPaymentConfiguration {
    @Bean MockOrderPaymentController mockOrderPaymentController(StorePaymentEvents payments) {
        return new MockOrderPaymentController(payments);
    }
    @Bean MockOrderPaymentExceptionHandler mockOrderPaymentExceptionHandler() { return new MockOrderPaymentExceptionHandler(); }
}
