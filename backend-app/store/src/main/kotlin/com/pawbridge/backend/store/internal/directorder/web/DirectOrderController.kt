package com.pawbridge.backend.store.internal.directorder.web

import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class DirectOrderController(private val orders: DirectOrders) {

    @PostMapping("/api/v1/orders/direct")
    fun create(
        @AuthenticationPrincipal current: Long?,
        @Valid @RequestBody request: DirectOrderCreateRequest,
    ): ResponseEntity<DirectOrders.OrderDetails> =
        ResponseEntity.status(201).body(
            orders.create(
                current(current),
                DirectOrders.Command(
                    request.skuId,
                    request.quantity,
                    request.receiverName,
                    request.receiverPhone,
                    request.deliveryAddress,
                    request.deliveryMessage,
                ),
            ),
        )

    private companion object {
        private fun current(member: Long?): Long {
            if (member == null) {
                throw AuthenticationCredentialsNotFoundException("An authenticated member is required")
            }
            return member
        }
    }
}
