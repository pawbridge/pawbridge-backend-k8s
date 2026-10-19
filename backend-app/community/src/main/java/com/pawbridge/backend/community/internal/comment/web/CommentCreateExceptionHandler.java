package com.pawbridge.backend.community.internal.comment.web;

import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CommentCreateController.class)
public final class CommentCreateExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommentCreateController.Response<Void>> invalidContent(MethodArgumentNotValidException failure) {
        String message = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return badRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommentCreateController.Response<Void>> unreadableBody(HttpMessageNotReadableException failure) {
        return badRequest("요청 본문 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<CommentCreateController.Response<Void>> postNotFound(PostNotFoundException failure) {
        return ResponseEntity.status(404)
                .body(new CommentCreateController.Response<>(404, "게시글을 찾을 수 없습니다.", null));
    }

    @ExceptionHandler(UnsupportedCommentCharactersException.class)
    public ResponseEntity<CommentCreateController.Response<Void>> unsupportedCharacters(UnsupportedCommentCharactersException failure) {
        return badRequest(failure.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<CommentCreateController.Response<Void>> serverError(RuntimeException failure) {
        return ResponseEntity.internalServerError()
                .body(new CommentCreateController.Response<>(500, "서버 에러!", null));
    }

    private ResponseEntity<CommentCreateController.Response<Void>> badRequest(String message) {
        return ResponseEntity.badRequest().body(new CommentCreateController.Response<>(400, message, null));
    }
}
