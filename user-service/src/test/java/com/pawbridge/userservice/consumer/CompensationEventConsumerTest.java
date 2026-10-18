package com.pawbridge.userservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.userservice.handler.CompensationEventHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.support.Acknowledgment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompensationEventConsumerTest {
    private final CompensationEventHandler handler = mock(CompensationEventHandler.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final CompensationEventConsumer consumer = new CompensationEventConsumer(handler, new ObjectMapper());
    private final String event = """
            {"eventId":"comp-1","originalEventId":"fav-1","compensationType":"ROLLBACK_FAVORITE_ADDED","userId":7,"animalId":42}
            """;

    @Test
    void successful_compensation_is_acknowledged_after_handler() {
        consumer.consumeCompensationEvent(event, ack);
        org.mockito.InOrder order = inOrder(handler, ack);
        order.verify(handler).rollbackFavoriteAdded(argThat(value -> value.getEventId().equals("comp-1")));
        order.verify(ack).acknowledge();
    }

    @Test
    void failed_compensation_is_not_acknowledged_and_can_be_retried() {
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(handler).rollbackFavoriteAdded(any());
        assertThatThrownBy(() -> consumer.consumeCompensationEvent(event, ack)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ack);
        consumer.consumeCompensationEvent(event, ack);
        verify(ack).acknowledge();
        verify(handler, times(2)).rollbackFavoriteAdded(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{}", "{\"compensationType\":\"UNKNOWN\"}"})
    void invalid_event_is_not_silently_acknowledged(String message) {
        assertThatThrownBy(() -> consumer.consumeCompensationEvent(message, ack)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(handler, ack);
    }
}
