package com.pawbridge.backend.community.internal.note.web;

import static org.junit.jupiter.api.Assertions.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class NoteSendExceptionHandlerTest {
    @Test
    void given__private_runtime_causes_and_suppressed_text__when__handling__then__return_generic_no_store_503_and_log_only_type_without_throwable(){
        var failure=new IllegalStateException("Bearer private-token body hash",new SQLException("private SQL"));failure.addSuppressed(new IllegalArgumentException("private nickname"));
        Logger logger=(Logger)LoggerFactory.getLogger(NoteSendExceptionHandler.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            var response=new NoteSendExceptionHandler().unexpectedFailure(failure);
            assertEquals(503,response.getStatusCode().value());assertEquals("no-store",response.getHeaders().getCacheControl());assertNull(response.getHeaders().getFirst("Set-Cookie"));
            assertEquals(Map.of("message","쪽지를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),response.getBody());
            assertEquals(1,appender.list.size());var event=appender.list.getFirst();assertEquals("Private note request failed (IllegalStateException)",event.getFormattedMessage());assertNull(event.getThrowableProxy());assertArrayEquals(new Object[]{"IllegalStateException"},event.getArgumentArray());
        }finally{logger.detachAppender(appender);appender.stop();}
    }

    @ParameterizedTest @ValueSource(strings={"MEMBER","STORAGE"})
    void given__typed_member_or_dao_failure__when__responding__then__keep_distinct_fixed_no_store_503_without_any_log(String source){
        Logger logger=(Logger)LoggerFactory.getLogger(NoteSendExceptionHandler.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            var handler=new NoteSendExceptionHandler();var response=source.equals("MEMBER")?handler.memberUnavailable():handler.storageUnavailable();
            assertEquals(503,response.getStatusCode().value());assertEquals("no-store",response.getHeaders().getCacheControl());assertNull(response.getHeaders().getFirst("Set-Cookie"));
            assertEquals(Map.of("message",source.equals("MEMBER")?"회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.":"쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),response.getBody());assertTrue(appender.list.isEmpty());
        }finally{logger.detachAppender(appender);appender.stop();}
    }
}
