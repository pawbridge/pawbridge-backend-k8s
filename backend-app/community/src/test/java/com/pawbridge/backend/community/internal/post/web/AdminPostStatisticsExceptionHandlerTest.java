package com.pawbridge.backend.community.internal.post.web;

import static org.junit.jupiter.api.Assertions.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AdminPostStatisticsExceptionHandlerTest {
    @Test
    void given__private_runtime_details__when__handling__then__return_original_500_and_log_only_type_without_new_cache_policy() {
        var logger = (Logger)LoggerFactory.getLogger(AdminPostStatisticsExceptionHandler.class);
        var events = new ListAppender<ILoggingEvent>(); events.start(); logger.addAppender(events);
        try {
            var response = new AdminPostStatisticsExceptionHandler().serverError(new IllegalStateException("private-body-sql-token", new RuntimeException("private-cause")));
            assertEquals(500, response.getStatusCode().value()); assertEquals(new ApiResponse<>(500, "서버 에러!", null), response.getBody());
            assertNull(response.getHeaders().getCacheControl()); assertEquals(1, events.list.size());
            assertEquals("Administrator post statistics failed (IllegalStateException)", events.list.getFirst().getFormattedMessage());
            assertNull(events.list.getFirst().getThrowableProxy());
        } finally { logger.detachAppender(events); events.stop(); }
    }
}
