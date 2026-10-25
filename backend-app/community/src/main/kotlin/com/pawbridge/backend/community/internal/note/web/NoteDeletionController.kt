package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.DeletePrivateNote
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteDeletionController(
    private val mutation: DeletePrivateNote,
) {

    @DeleteMapping("/api/v1/notes/{id}")
    fun delete(
        @AuthenticationPrincipal currentUserId: Long?,
        @PathVariable("id") noteId: UUID,
    ): ResponseEntity<Void> {
        mutation.delete(currentUserId!!, noteId)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build()
    }
}
