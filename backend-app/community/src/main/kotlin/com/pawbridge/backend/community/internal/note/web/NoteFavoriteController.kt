package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteFavoriteController(
    private val mutation: SetPrivateNoteFavorite,
) {

    @PutMapping("/api/v1/notes/{id}/favorite")
    fun set(
        @AuthenticationPrincipal currentUserId: Long?,
        @PathVariable("id") noteId: UUID,
        @RequestBody request: FavoriteRequest,
    ): ResponseEntity<Void> {
        mutation.set(currentUserId!!, noteId, request.favorite)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build()
    }

    @JvmRecord
    data class FavoriteRequest(
        val favorite: Boolean,
    )
}
