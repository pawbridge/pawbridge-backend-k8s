package com.pawbridge.backend.user.internal.statistics.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TotalUserCountExceptionHandlerTest {
    @Test
    void given__private_runtime_cause_and_suppressed_messages__when__handling__then__return_common_500_without_custom_cache_and_log_only_type() {
        var failure = new IllegalStateException("Bearer synthetic-private-token",
                new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"));
        failure.addSuppressed(new IllegalArgumentException("synthetic-private-member"));
        Logger logger = (Logger) LoggerFactory.getLogger(TotalUserCountExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new TotalUserCountExceptionHandler().serverError(failure);

            assertEquals(500, response.getStatusCode().value());
            assertEquals(new TotalUserCountController.Response<Void>(500, "서버 에러!", null), response.getBody());
            assertNull(response.getHeaders().getCacheControl());
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertEquals("Total user count failed (IllegalStateException)", event.getFormattedMessage());
            assertNull(event.getThrowableProxy());
            assertEquals(1, event.getArgumentArray().length);
            assertEquals("IllegalStateException", event.getArgumentArray()[0]);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
