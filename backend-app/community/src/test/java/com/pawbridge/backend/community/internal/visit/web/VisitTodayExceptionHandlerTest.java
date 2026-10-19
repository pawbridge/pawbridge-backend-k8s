package com.pawbridge.backend.community.internal.visit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class VisitTodayExceptionHandlerTest {
    @Test
    void given__storage_failure__when__handling__then__return_exact_503_envelope_with_no_store() {
        var response = new VisitTodayExceptionHandler().storageUnavailable();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(new VisitTodayController.Response<Void>(503, "방문 통계를 불러올 수 없습니다.", null),
                response.getBody());
    }

    @Test
    void given__runtime_with_private_messages_and_causes__when__handling__then__return_500_without_new_cache_policy_and_log_only_type() {
        var failure = new IllegalStateException("Bearer synthetic-private-token",
                new SQLException("jdbc:postgresql://fixture.invalid/db?password=synthetic-private-password"));
        failure.addSuppressed(new IllegalArgumentException("synthetic-private-user"));
        Logger logger = (Logger) LoggerFactory.getLogger(VisitTodayExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = new VisitTodayExceptionHandler().serverError(failure);

            assertEquals(500, response.getStatusCode().value());
            assertNull(response.getHeaders().getCacheControl());
            assertEquals(new VisitTodayController.Response<Void>(500, "서버 에러!", null), response.getBody());
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertNull(event.getThrowableProxy());
            assertEquals("Visit today request failed (IllegalStateException)", event.getFormattedMessage());
            assertEquals(1, event.getArgumentArray().length);
            assertEquals("IllegalStateException", event.getArgumentArray()[0]);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
