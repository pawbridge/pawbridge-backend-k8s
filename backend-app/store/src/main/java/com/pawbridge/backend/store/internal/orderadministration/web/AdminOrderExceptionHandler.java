package com.pawbridge.backend.store.internal.orderadministration.web;

import com.pawbridge.backend.store.internal.orderadministration.application.AdministratorRejectedException;
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrderNotFoundException;
import com.pawbridge.backend.store.internal.directorder.infrastructure.PostgresqlRollbackCharsetViolation;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Scoped errors contain no order/address/account/SQL/exception body logs. */
@RestControllerAdvice(assignableTypes=AdminOrderController.class)
public final class AdminOrderExceptionHandler {
    public record FieldError(String field,String value,String reason) { }
    public record Error(String message,String code,List<FieldError> errors) { }
    @ExceptionHandler(AuthenticationException.class) public ResponseEntity<Void> authentication(AuthenticationException failure) {return ResponseEntity.status(401).build();}
    @ExceptionHandler(AdministratorRejectedException.class) public ResponseEntity<Error> role(AdministratorRejectedException failure) {return ResponseEntity.status(403).body(new Error("Access is Denied","C006",List.of()));}
    @ExceptionHandler(AdminOrderNotFoundException.class) public ResponseEntity<Error> missing(AdminOrderNotFoundException failure) {return ResponseEntity.status(404).body(new Error("Order not found","O001",List.of()));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Error> invalid(IllegalArgumentException failure) {return ResponseEntity.badRequest().body(new Error("Invalid Input Value","C001",List.of()));}
    @ExceptionHandler(MethodArgumentTypeMismatchException.class) public ResponseEntity<Error> type(MethodArgumentTypeMismatchException failure) {
        return ResponseEntity.badRequest().body(new Error("Invalid Type Value","C005",List.of(new FieldError(failure.getName(),failure.getValue()==null?"":failure.getValue().toString(),failure.getErrorCode()))));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<Error> unexpected(Exception failure) {
        if(PostgresqlRollbackCharsetViolation.matches(failure))return ResponseEntity.badRequest().body(new Error(PostgresqlRollbackCharsetViolation.MESSAGE,"C007",List.of()));
        return ResponseEntity.internalServerError().body(new Error("Server Error","C004",List.of()));
    }
}
