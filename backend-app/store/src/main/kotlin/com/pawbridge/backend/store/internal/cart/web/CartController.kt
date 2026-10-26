package com.pawbridge.backend.store.internal.cart.web

import com.pawbridge.backend.store.internal.cart.application.Carts
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/carts")
class CartController(private val carts: Carts) {

    @GetMapping
    fun get(@AuthenticationPrincipal user: Long?): ResponseEntity<List<Carts.Item>> =
        ResponseEntity.ok(carts.get(current(user)))

    @PostMapping("/items")
    fun add(
        @AuthenticationPrincipal user: Long?,
        @RequestBody request: CartAddRequest,
    ): ResponseEntity<Void> {
        carts.add(current(user), request.skuId, request.quantity)
        return ResponseEntity.ok().build()
    }

    @PatchMapping("/items/{skuId}")
    fun update(
        @AuthenticationPrincipal user: Long?,
        @PathVariable("skuId") skuId: Long,
        @RequestParam("quantity") quantity: Int,
    ): ResponseEntity<Void> {
        carts.update(current(user), skuId, quantity)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/items/{skuId}")
    fun remove(
        @AuthenticationPrincipal user: Long?,
        @PathVariable("skuId") skuId: Long,
    ): ResponseEntity<Void> {
        carts.remove(current(user), skuId)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping
    fun clear(@AuthenticationPrincipal user: Long?): ResponseEntity<Void> {
        carts.clear(current(user))
        return ResponseEntity.ok().build()
    }

    private companion object {
        private fun current(user: Long?): Long {
            if (user == null) {
                throw AuthenticationCredentialsNotFoundException("An authenticated member is required")
            }
            return user
        }
    }
}
