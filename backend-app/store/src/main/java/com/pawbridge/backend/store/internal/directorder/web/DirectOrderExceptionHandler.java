package com.pawbridge.backend.store.internal.directorder.web;

import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import com.pawbridge.backend.store.internal.directorder.infrastructure.PostgresqlRollbackCharsetViolation;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** No request, account, shipping, token, response or exception-body logging. */
@RestControllerAdvice(assignableTypes=DirectOrderController.class)
public final class DirectOrderExceptionHandler {
    public record FieldError(String field,String value,String reason) { }
    public record Error(String message,String code,List<FieldError> errors) { }
    @ExceptionHandler(AuthenticationException.class) public ResponseEntity<Void> unauthenticated(AuthenticationException failure) {return ResponseEntity.status(401).build();}
    @ExceptionHandler(OrderActorRejectedException.class) public ResponseEntity<Error> actorRejected(OrderActorRejectedException failure) {
        return ResponseEntity.status(403).body(new Error("Access is Denied","C006",List.of()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Error> invalid(IllegalArgumentException failure) {return ResponseEntity.badRequest().body(new Error("Invalid Input Value","C001",List.of()));}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Error> validation(MethodArgumentNotValidException failure) {
        return ResponseEntity.badRequest().body(new Error("Invalid Input Value","C001",failure.getBindingResult().getFieldErrors().stream()
                .map(error->new FieldError(error.getField(),error.getRejectedValue()==null?"":error.getRejectedValue().toString(),error.getDefaultMessage())).toList()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class) public ResponseEntity<Error> typeMismatch(MethodArgumentTypeMismatchException failure) {
        return ResponseEntity.badRequest().body(new Error("Invalid Type Value","C005",List.of(new FieldError(failure.getName(),
                failure.getValue()==null?"":failure.getValue().toString(),failure.getErrorCode()))));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<Error> unexpected(Exception failure) {
        if(PostgresqlRollbackCharsetViolation.matches(failure))
            return ResponseEntity.badRequest().body(new Error(PostgresqlRollbackCharsetViolation.MESSAGE,"C007",List.of()));
        return ResponseEntity.internalServerError().body(new Error("Server Error","C004",List.of()));
    }
}
