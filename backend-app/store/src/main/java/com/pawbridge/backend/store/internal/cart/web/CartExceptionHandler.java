package com.pawbridge.backend.store.internal.cart.web;

import com.pawbridge.backend.store.internal.cart.application.CartActorRejectedException;
import com.pawbridge.backend.store.internal.directorder.infrastructure.PostgresqlRollbackCharsetViolation;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Scoped errors never log request, account, token, response, SQL or exception bodies. */
@RestControllerAdvice(assignableTypes=CartController.class)
public final class CartExceptionHandler {
    public record FieldError(String field,String value,String reason) { }
    public record Error(String message,String code,List<FieldError> errors) { }
    @ExceptionHandler(AuthenticationException.class) public ResponseEntity<Void> unauthenticated(AuthenticationException failure) {return ResponseEntity.status(401).build();}
    @ExceptionHandler(CartActorRejectedException.class) public ResponseEntity<Error> actor(CartActorRejectedException failure) {return ResponseEntity.status(403).body(new Error("Access is Denied","C006",List.of()));}
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<Error> invalid(Exception failure) {return ResponseEntity.badRequest().body(new Error("Invalid Input Value","C001",List.of()));}
    @ExceptionHandler(MethodArgumentTypeMismatchException.class) public ResponseEntity<Error> type(MethodArgumentTypeMismatchException failure) {
        return ResponseEntity.badRequest().body(new Error("Invalid Type Value","C005",List.of(new FieldError(failure.getName(),failure.getValue()==null?"":failure.getValue().toString(),failure.getErrorCode()))));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<Error> unexpected(Exception failure) {
        if(PostgresqlRollbackCharsetViolation.matches(failure))return ResponseEntity.badRequest().body(new Error(PostgresqlRollbackCharsetViolation.MESSAGE,"C007",List.of()));
        return ResponseEntity.internalServerError().body(new Error("Server Error","C004",List.of()));
    }
}
