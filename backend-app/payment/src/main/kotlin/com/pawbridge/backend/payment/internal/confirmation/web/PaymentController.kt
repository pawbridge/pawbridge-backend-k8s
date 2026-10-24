package com.pawbridge.backend.payment.internal.confirmation.web

import com.pawbridge.backend.payment.internal.confirmation.application.Payments
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Original route and bare response; verified principal replaces the old gateway-supplied identity header. */
@RestController
class PaymentController(private val payments: Payments) {

    // The body stays non-null: a nullable Kotlin @RequestBody would make the body optional for Spring MVC.
    @PostMapping("/api/v1/payments/confirm")
    fun confirmPayment(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @Valid @RequestBody request: TossPaymentConfirmRequest,
    ): ResponseEntity<TossPaymentResponse> {
        if (verifiedUserId == null) {
            throw AuthenticationCredentialsNotFoundException("An authenticated member is required")
        }
        return ResponseEntity.ok(payments.confirmPayment(verifiedUserId, request))
    }
}
