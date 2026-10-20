package com.pawbridge.backend.store.internal.catalog.web;

import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = StoreCatalogController.class)
public final class StoreCatalogExceptionHandler {
    public record FieldError(String field,String value,String reason) { }
    public record Error(String message,String code,List<FieldError> errors) { }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Error> invalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(new Error("Invalid Input Value","C001",List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Error> wrongType(MethodArgumentTypeMismatchException failure) {
        return ResponseEntity.badRequest().body(new Error("Invalid Type Value","C005",List.of(new FieldError(
                failure.getName(),failure.getValue()==null?"":failure.getValue().toString(),failure.getErrorCode()))));
    }

    @ExceptionHandler(CatalogSearchUnavailableException.class)
    public ResponseEntity<Error> searchUnavailable(CatalogSearchUnavailableException failure) {
        return ResponseEntity.status(503).body(new Error("Search temporarily unavailable","P006",List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> serverError(Exception failure) {
        return ResponseEntity.internalServerError().body(new Error("Server Error","C004",List.of()));
    }
}
