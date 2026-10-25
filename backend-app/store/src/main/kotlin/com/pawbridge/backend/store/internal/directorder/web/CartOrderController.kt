package com.pawbridge.backend.store.internal.directorder.web

import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class CartOrderController(private val orders: CartOrders) {

    @PostMapping("/api/v1/orders")
    fun create(
        @AuthenticationPrincipal user: Long?,
        @RequestBody request: CartOrderCreateRequest,
    ): ResponseEntity<DirectOrders.OrderDetails> {
        if (user == null) {
            throw AuthenticationCredentialsNotFoundException("An authenticated member is required")
        }
        return ResponseEntity.status(201).body(
            orders.create(
                user,
                CartOrders.Command(
                    request.receiverName,
                    request.receiverPhone,
                    request.deliveryAddress,
                    request.deliveryMessage,
                ),
            ),
        )
    }
}
