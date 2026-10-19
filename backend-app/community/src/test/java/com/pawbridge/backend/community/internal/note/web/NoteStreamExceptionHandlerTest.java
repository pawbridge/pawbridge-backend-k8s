package com.pawbridge.backend.community.internal.note.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class NoteStreamExceptionHandlerTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void givenPrivateRuntimeAndResponseCommitState__whenHandlingStreamFailure__thenLogTypeOnlyAndNeverWriteJsonAfterCommit(boolean committed) {
        var response = mock(HttpServletResponse.class); when(response.isCommitted()).thenReturn(committed);
        var failure = new IllegalStateException("Bearer synthetic-private", new RuntimeException("private SQL/body"));
        failure.addSuppressed(new RuntimeException("private credentials"));
        Logger logger = (Logger) LoggerFactory.getLogger(NoteStreamExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var result = new NoteStreamExceptionHandler().unexpectedFailure(failure, response);
            if (committed) assertThat(result).isNull();
            else {
                assertThat(result.getStatusCode().value()).isEqualTo(503);
                assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
                assertThat(result.getBody()).isEqualTo(Map.of("message", "쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
            }
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage()).isEqualTo("Private note request failed (IllegalStateException)");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
            assertThat(appender.list.getFirst().getArgumentArray()).containsExactly("IllegalStateException");
            if (committed) verify(response, never()).setHeader(anyString(), anyString());
            else verify(response).setHeader("Cache-Control", "no-store");
            verify(response).isCommitted(); verifyNoMoreInteractions(response);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
