package com.pawbridge.backend.community.internal.report.web;

import com.pawbridge.backend.community.internal.post.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes=AnimalReportController.class)
public final class AnimalReportExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(AnimalReportExceptionHandler.class);
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<ApiResponse<Void>> rejected(ResponseStatusException failure){
        int code=failure.getStatusCode().value();
        return ResponseEntity.status(code).body(new ApiResponse<>(code,
                failure.getStatusCode().is4xxClientError() && failure.getReason()!=null?failure.getReason():"요청을 처리하지 못했습니다.",null));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class) public ResponseEntity<ApiResponse<Void>> invalidParameter(){
        return ResponseEntity.badRequest().body(new ApiResponse<>(400,"제보 검색의 종류·날짜·페이지 형식을 확인해 주세요.",null));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<ApiResponse<Void>> invalidBody(){
        return ResponseEntity.badRequest().body(new ApiResponse<>(400,"요청 본문 형식이 올바르지 않습니다.",null));
    }
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<ApiResponse<Void>> failure(RuntimeException failure){
        LOG.error("Report request failed (exception message omitted): {}",failure.getClass().getName());
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500,"서버 에러!",null));
    }
}
