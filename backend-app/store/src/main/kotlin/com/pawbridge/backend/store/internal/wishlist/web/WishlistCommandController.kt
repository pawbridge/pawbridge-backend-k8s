package com.pawbridge.backend.store.internal.wishlist.web

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Only explicitly assembled by Root with authentication and default-off activation gates. */
@RestController
class WishlistCommandController(private val commands: WishlistCommands) {

    @JvmRecord
    data class AddRequest(val skuId: Long?)

    @PostMapping("/api/v1/wishlists")
    fun add(
        @AuthenticationPrincipal current: Long?,
        @RequestBody request: AddRequest,
    ): ResponseEntity<WishlistCommands.Added> =
        ResponseEntity.status(201).body(commands.add(currentMember(current), request.skuId))

    @DeleteMapping("/api/v1/wishlists")
    fun removeBySku(
        @AuthenticationPrincipal current: Long?,
        @RequestParam("skuId") skuId: Long,
    ): ResponseEntity<Void> {
        commands.removeByMemberAndSku(currentMember(current), skuId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/api/v1/wishlists/{wishlistId}")
    fun removeById(
        @AuthenticationPrincipal current: Long?,
        @PathVariable("wishlistId") wishlistId: Long,
    ): ResponseEntity<Void> {
        commands.removeById(currentMember(current), wishlistId)
        return ResponseEntity.noContent().build()
    }

    private companion object {
        private fun currentMember(member: Long?): Long {
            if (member == null) {
                throw AuthenticationCredentialsNotFoundException("An authenticated member is required")
            }
            return member
        }
    }
}
