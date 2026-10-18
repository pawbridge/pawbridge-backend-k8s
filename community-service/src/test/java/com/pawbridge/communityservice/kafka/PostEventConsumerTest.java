package com.pawbridge.communityservice.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PostEventConsumerTest {

    private static final String EVENT_ID = "event-post-42";
    private static final String POST_JSON = """
            {"postId":42,"authorId":7,"title":"Test post","content":"Test content",
             "boardType":"FREE","imageUrls":["https://example.com/test.png"]}
            """;
    private static final Map<String, Object> POST = Map.of(
            "postId", 42, "authorId", 7, "title", "Test post", "content", "Test content",
            "boardType", "FREE", "imageUrls", List.of("https://example.com/test.png"));

    private final PostEventHandler handler = mock(PostEventHandler.class);
    private final PostEventConsumer consumer = new PostEventConsumer(handler, new ObjectMapper());

    @Test
    void givenFlatCreatedEvent__whenConsumed__thenIndexesPost() {
        consumer.consumePostEvent(record("POST_CREATED", POST_JSON));

        verify(handler).indexPost(EVENT_ID, POST);
        verifyNoMoreInteractions(handler);
    }

    @Test
    void givenFlatUpdatedEvent__whenConsumed__thenUpdatesPost() {
        consumer.consumePostEvent(record("POST_UPDATED", POST_JSON));

        verify(handler).updatePost(EVENT_ID, POST);
        verifyNoMoreInteractions(handler);
    }

    @Test
    void givenFlatDeletedEvent__whenConsumed__thenDeletesPost() {
        consumer.consumePostEvent(record("POST_DELETED", "{\"postId\":42}"));

        verify(handler).deletePost(EVENT_ID, Map.of("postId", 42));
        verifyNoMoreInteractions(handler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "eventType"})
    void givenMissingHeader__whenConsumed__thenFails(String header) {
        ConsumerRecord<String, String> record = record("POST_CREATED", POST_JSON);
        record.headers().remove(header);

        assertThatThrownBy(() -> consumer.consumePostEvent(record)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(handler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "eventType"})
    void givenNullHeader__whenConsumed__thenFails(String header) {
        ConsumerRecord<String, String> record = record("POST_CREATED", POST_JSON);
        record.headers().remove(header).add(header, null);

        assertThatThrownBy(() -> consumer.consumePostEvent(record)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(handler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "eventType"})
    void givenBlankHeader__whenConsumed__thenFails(String header) {
        ConsumerRecord<String, String> record = record("POST_CREATED", POST_JSON);
        record.headers().remove(header).add(header, "  ".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> consumer.consumePostEvent(record)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(handler);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null", "[]", "\"text\"", "123", "{broken", "{}"})
    void givenInvalidBody__whenConsumed__thenFails(String body) {
        assertThatThrownBy(() -> consumer.consumePostEvent(record("POST_CREATED", body)))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(handler);
    }

    @Test
    void givenUnknownEventType__whenConsumed__thenFails() {
        assertThatThrownBy(() -> consumer.consumePostEvent(record("POST_UNKNOWN", POST_JSON)))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(handler);
    }

    @Test
    void givenHandlerFailure__whenConsumed__thenPropagatesForRetry() {
        IllegalStateException failure = new IllegalStateException("Search temporarily unavailable");
        doThrow(failure).when(handler).indexPost(EVENT_ID, POST);

        assertThatThrownBy(() -> consumer.consumePostEvent(record("POST_CREATED", POST_JSON)))
                .isInstanceOf(RuntimeException.class).hasCause(failure);
        verify(handler).indexPost(EVENT_ID, POST);
        verifyNoMoreInteractions(handler);
    }

    private ConsumerRecord<String, String> record(String eventType, String body) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("community.post.events", 0, 1L, "42", body);
        record.headers().add("id", EVENT_ID.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
