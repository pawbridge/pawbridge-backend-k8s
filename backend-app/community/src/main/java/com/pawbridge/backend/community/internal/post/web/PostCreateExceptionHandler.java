package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.UnsupportedPostCharactersException;
import com.pawbridge.backend.community.internal.post.domain.PostBoardNotAllowedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PostCreateController.class)
public final class PostCreateExceptionHandler {
    @ExceptionHandler(PostBoardNotAllowedException.class)
    public ResponseEntity<ApiResponse<Void>> boardNotAllowed(PostBoardNotAllowedException failure) {
        return badRequest(failure.getMessage());
    }

    @ExceptionHandler(UnsupportedPostCharactersException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedCharacters(UnsupportedPostCharactersException failure) {
        return badRequest(failure.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> missingParameter(MissingServletRequestParameterException failure) {
        return badRequest("필수 요청 파라미터 '" + failure.getParameterName() + "'(이)가 누락되었습니다.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> serverError(RuntimeException failure) {
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "서버 에러!", null));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, message, null));
    }
}
