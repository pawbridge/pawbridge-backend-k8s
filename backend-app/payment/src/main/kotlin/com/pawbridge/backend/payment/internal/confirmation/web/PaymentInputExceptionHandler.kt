package com.pawbridge.backend.payment.internal.confirmation.web

import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderAccessDeniedException
import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderNotPayableException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Existing PostgreSQL input rejection plus the pre-provider order checks, scoped to the confirmation controller. */
@RestControllerAdvice(assignableTypes = [PaymentController::class])
class PaymentInputExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleInvalidInput(error: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(
            ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "일부 이모지 등 지원하지 않는 문자를 제외해 주세요."),
        )

    @ExceptionHandler(PaymentOrderAccessDeniedException::class)
    fun handleForeignOrder(error: PaymentOrderAccessDeniedException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "본인의 주문만 결제할 수 있습니다."),
        )

    @ExceptionHandler(PaymentOrderNotPayableException::class)
    fun handleNotPayable(error: PaymentOrderNotPayableException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "결제 대기 상태의 주문만 결제할 수 있습니다."),
        )
}
