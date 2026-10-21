package com.pawbridge.backend.payment.internal.confirmation.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Existing PostgreSQL input rejection, scoped to the relocated confirmation controller. */
@RestControllerAdvice(assignableTypes = PaymentController.class)
public final class PaymentInputExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleInvalidInput(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요."));
    }
}
