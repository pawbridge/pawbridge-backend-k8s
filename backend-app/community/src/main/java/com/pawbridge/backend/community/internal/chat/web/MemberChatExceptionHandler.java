package com.pawbridge.backend.community.internal.chat.web;

import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=MemberChatController.class)
public final class MemberChatExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(MemberChatExceptionHandler.class);
    @ExceptionHandler(MemberChatRejectedException.class)
    public ResponseEntity<MemberChatController.Response<Void>> rejected(MemberChatRejectedException failure){
        String message=failure.status()>=400&&failure.status()<500?failure.getMessage():"요청을 처리하지 못했습니다.";
        return ResponseEntity.status(failure.status()).body(new MemberChatController.Response<>(failure.status(),message,null));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MemberChatController.Response<Void>> unreadable(){return ResponseEntity.badRequest().body(new MemberChatController.Response<>(400,"요청 본문 형식이 올바르지 않습니다.",null));}
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<MemberChatController.Response<Void>> failed(RuntimeException failure){
        LOG.error("Member chat request failed; exceptionType={}",failure.getClass().getName());
        return ResponseEntity.internalServerError().body(new MemberChatController.Response<>(500,"서버 에러!",null));
    }
}
