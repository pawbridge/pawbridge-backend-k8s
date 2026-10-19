package com.pawbridge.backend.community.internal.note.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class NoteRecipientExceptionHandlerTest {
    @Test
    void given__private_runtime_cause_and_suppressed_messages__when__handling__then__return_no_store_message_only_503_and_log_only_type() {
        var failure = new IllegalStateException("Bearer synthetic-private-token",
                new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"));
        failure.addSuppressed(new IllegalArgumentException("synthetic-private-member"));
        Logger logger = (Logger) LoggerFactory.getLogger(NoteRecipientExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new NoteRecipientExceptionHandler().unexpectedFailure(failure);

            assertEquals(503, response.getStatusCode().value());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertEquals(Map.of("message", "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."), response.getBody());
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertEquals("Private note request failed (IllegalStateException)", event.getFormattedMessage());
            assertNull(event.getThrowableProxy());
            assertEquals(1, event.getArgumentArray().length);
            assertEquals("IllegalStateException", event.getArgumentArray()[0]);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
