package com.pawbridge.animalservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.service.OutboxService;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FavoriteRecoveryTest {
    private final OutboxService outbox = mock(OutboxService.class);
    private final Consumer<?,?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    private boolean recover(Map<String,Object> payload) {
        when(container.isRunning()).thenReturn(true);
        DefaultErrorHandler handler = (DefaultErrorHandler) new KafkaConsumerConfig(outbox,new ObjectMapper()).errorHandler();
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        // Classify this synthetic failure as fatal to exercise recovery without retry waits.
        return handler.handleOne(new IllegalArgumentException("cannot apply"),
                new ConsumerRecord<>("user.favorite.events",0,42L,"100",payload),consumer,container);
    }
    private Map<String,Object> event(String type) {
        return Map.of("eventId","original","eventType",type,"userId",100L,"animalId",100L);
    }
    @Test void failed_compensation_storage_does_not_recover_record() {
        doThrow(new IllegalStateException("DB unavailable")).when(outbox).saveEvent(anyString(),anyString(),anyString(),anyString(),any());
        assertThat(recover(event("FAVORITE_ADDED"))).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"FAVORITE_REMOVED","UNKNOWN"})
    void unresolved_event_is_not_silently_discarded(String type) {
        assertThat(recover(event(type))).isFalse(); verifyNoInteractions(outbox);
    }
    @Test void invalid_original_id_cannot_be_replaced_by_an_untraceable_compensation() {
        assertThat(recover(Map.of("eventType","FAVORITE_ADDED","userId",100L,"animalId",100L))).isFalse();
        verifyNoInteractions(outbox);
    }
    @Test void durably_saved_compensation_is_a_recovered_record() {
        assertThat(recover(event("FAVORITE_ADDED"))).isTrue();
        verify(outbox).saveEvent(eq("FavoriteCompensation"),eq("100"),eq("FAVORITE_COMPENSATION_REQUIRED"),
                eq("user.compensation.events"),argThat(value -> value instanceof com.pawbridge.animalservice.event.FavoriteCompensationEvent
                        && "original".equals(((com.pawbridge.animalservice.event.FavoriteCompensationEvent)value).getOriginalEventId())));
    }
}
