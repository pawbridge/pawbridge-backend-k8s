@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.pawbridge.backend.store.internal.payment.web

import com.pawbridge.backend.store.api.StorePaymentEvents
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

// Path ids use java.lang.Long deliberately: kotlin.Long? makes the variable optional (a blank segment reaches the
// service as null) and kotlin.Long is a primitive (a blank segment becomes a type mismatch). A non-null
// java.lang.Long keeps the Java behaviour: required and boxed, so a blank segment fails before the handler runs.

/** Original mock payment route: no owner, amount or provider check, kept as-is behind its own flag. */
@RestController
class MockOrderPaymentController(private val payments: StorePaymentEvents) {

    @PostMapping("/api/v1/orders/{orderId}/payment")
    fun processPayment(@PathVariable("orderId") orderId: java.lang.Long): ResponseEntity<Void> {
        payments.processPayment(orderId.toLong())
        return ResponseEntity.ok().build()
    }
}
