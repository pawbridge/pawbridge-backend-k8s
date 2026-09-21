package com.pawbridge.storeservice.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {
    @Bean
    public DefaultErrorHandler outboxErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, failure) -> {
                    // No durable DLT is configured: keep the failed offset for retry/operator repair.
                    throw new IllegalStateException("Outbox event recovery is unresolved", failure);
                }, new FixedBackOff(1000L, 4L));
        handler.setAckAfterHandle(false);
        return handler;
    }
}
