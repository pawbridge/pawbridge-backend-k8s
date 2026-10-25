package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteReadMutationController(
    private val mutation: MarkPrivateNoteRead,
) {

    @PutMapping("/api/v1/notes/{id}/read")
    fun read(
        @AuthenticationPrincipal currentUserId: Long?,
        @PathVariable("id") noteId: UUID,
    ): ResponseEntity<Void> {
        mutation.read(currentUserId!!, noteId)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build()
    }
}
