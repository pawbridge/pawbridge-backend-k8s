package com.pawbridge.backend.store.internal.catalogwrite.web;

import com.pawbridge.backend.store.internal.directorder.infrastructure.PostgresqlRollbackCharsetViolation;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Original Store error envelope/statuses, scoped to the added handlers and without payload logging. */
@RestControllerAdvice(assignableTypes={ProductWriteController.class,OptionManagementController.class,ProductImageController.class})
public final class CatalogManagementExceptionHandler {
    public record FieldError(String field,String value,String reason){}
    public record Error(String message,String code,List<FieldError> errors){}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Error> invalid(IllegalArgumentException failure){
        return ResponseEntity.badRequest().body(new Error("Invalid Input Value","C001",List.of()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class) public ResponseEntity<Error> typeMismatch(MethodArgumentTypeMismatchException failure){
        return ResponseEntity.badRequest().body(new Error("Invalid Type Value","C005",List.of(new FieldError(failure.getName(),
                failure.getValue()==null?"":failure.getValue().toString(),failure.getErrorCode()))));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) public ResponseEntity<Error> method(HttpRequestMethodNotSupportedException failure){
        return ResponseEntity.status(405).body(new Error("Method Not Allowed","C002",List.of()));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<Error> unexpected(Exception failure){
        if(PostgresqlRollbackCharsetViolation.matches(failure))
            return ResponseEntity.badRequest().body(new Error(PostgresqlRollbackCharsetViolation.MESSAGE,"C007",List.of()));
        return ResponseEntity.internalServerError().body(new Error("Server Error","C004",List.of()));
    }
}
