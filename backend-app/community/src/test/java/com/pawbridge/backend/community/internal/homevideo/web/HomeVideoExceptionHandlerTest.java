package com.pawbridge.backend.community.internal.homevideo.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class HomeVideoExceptionHandlerTest {
    @Test
    void given__private_failure_messages_causes_and_suppressed_data__when__handling__then__return_safe_503_and_log_only_exception_type() {
        var failure = new IllegalStateException("Bearer synthetic-private-token",
                new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"));
        failure.addSuppressed(new IllegalArgumentException("https://fixture.invalid/video?key=synthetic-private-key"));
        Logger logger = (Logger) LoggerFactory.getLogger(HomeVideoExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new HomeVideoExceptionHandler().failure(failure);

            assertEquals(503, response.getStatusCode().value());
            assertEquals(new HomeVideoController.Response<Void>(503,
                    "영상을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", null), response.getBody());
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertNull(event.getThrowableProxy());
            assertEquals("Video curation request failed (IllegalStateException)", event.getFormattedMessage());
            assertEquals(1, event.getArgumentArray().length);
            assertEquals("IllegalStateException", event.getArgumentArray()[0]);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
