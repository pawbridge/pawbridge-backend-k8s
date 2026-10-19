package com.pawbridge.backend.community.internal.note.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class NoteFavoriteExceptionHandlerTest {
    @Test
    void given__private_runtime_cause_and_suppressed_messages__when__handling__then__return_no_store_generic_503_and_log_type_without_throwable() {
        var failure = new IllegalStateException("Bearer synthetic-private-token", new SQLException("synthetic-private-SQL"));
        failure.addSuppressed(new IllegalArgumentException("synthetic-private-member-and-note-body"));
        Logger logger = (Logger) LoggerFactory.getLogger(NoteFavoriteExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var response = new NoteFavoriteExceptionHandler().unexpectedFailure(failure);

            assertEquals(503, response.getStatusCode().value());
            assertEquals("no-store", response.getHeaders().getCacheControl()); assertNull(response.getHeaders().getFirst("Set-Cookie"));
            assertEquals(Map.of("message", "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."), response.getBody());
            assertEquals(1, appender.list.size()); var event = appender.list.getFirst();
            assertEquals("Private note request failed (IllegalStateException)", event.getFormattedMessage());
            assertNull(event.getThrowableProxy()); assertEquals(1, event.getArgumentArray().length);
            assertEquals("IllegalStateException", event.getArgumentArray()[0]);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "STORAGE"})
    void given__typed_member_or_storage_handler__when__responding__then__keep_fixed_503_no_store_without_any_log(String source) {
        Logger logger = (Logger) LoggerFactory.getLogger(NoteFavoriteExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var handler = new NoteFavoriteExceptionHandler();
            var response = source.equals("MEMBER") ? handler.memberUnavailable() : handler.storageUnavailable();

            assertEquals(503, response.getStatusCode().value());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertNull(response.getHeaders().getFirst("Set-Cookie"));
            assertEquals(Map.of("message", source.equals("MEMBER")
                    ? "회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
                    : "쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."), response.getBody());
            assertEquals(0, appender.list.size());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
