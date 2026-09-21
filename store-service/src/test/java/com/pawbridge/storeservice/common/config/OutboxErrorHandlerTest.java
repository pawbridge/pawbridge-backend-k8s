package com.pawbridge.storeservice.common.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxErrorHandlerTest {
    @Test
    void retry_exhaustion_never_marks_failed_event_recovered() {
        DefaultErrorHandler handler = new KafkaConsumerConfig().outboxErrorHandler();
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test.events", 0, 42L, "key", "{}");
        for (int attempt = 0; attempt < 6; attempt++) {
            assertThat(handler.handleOne(new IllegalStateException("storage failed"), record, consumer, container)).isFalse();
        }
        assertThat(handler.isAckAfterHandle()).isFalse();
        verifyNoInteractions(consumer);
    }
}
