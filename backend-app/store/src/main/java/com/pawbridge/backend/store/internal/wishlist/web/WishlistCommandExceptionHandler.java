package com.pawbridge.backend.store.internal.wishlist.web;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=WishlistCommandController.class)
public final class WishlistCommandExceptionHandler {
    public record FieldError(String field,String value,String reason) { }
    public record Error(String message,String code,List<FieldError> errors) { }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Void> unauthenticated(AuthenticationException failure) { return ResponseEntity.status(401).build(); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Error> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(new Error("Invalid Input Value","C001",List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Error> typeMismatch(MethodArgumentTypeMismatchException failure) {
        return ResponseEntity.badRequest().body(new Error("Invalid Type Value","C005",List.of(new FieldError(
                failure.getName(),failure.getValue()==null?"":failure.getValue().toString(),failure.getErrorCode()))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> serverError(Exception failure) {
        return ResponseEntity.internalServerError().body(new Error("Server Error","C004",List.of()));
    }
}
