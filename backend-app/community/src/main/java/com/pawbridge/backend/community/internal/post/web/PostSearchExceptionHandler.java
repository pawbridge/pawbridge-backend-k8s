package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.InvalidPostSearchInputException;
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PostSearchController.class)
public final class PostSearchExceptionHandler {
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> missingKeyword(MissingServletRequestParameterException failure) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400,
                "필수 요청 파라미터 '" + failure.getParameterName() + "'(이)가 누락되었습니다.", null));
    }

    @ExceptionHandler(InvalidPostSearchInputException.class)
    public ResponseEntity<ApiResponse<Void>> invalidInput(InvalidPostSearchInputException failure) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "검색 입력을 확인해 주세요.", null));
    }

    @ExceptionHandler(PostSearchUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(PostSearchUnavailableException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(503, "검색 서비스를 사용할 수 없습니다.", null));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> serverError(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>(500, "서버 에러!", null));
    }
}
