package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteBlockRemoveController(
    private val removal: RemoveNoteBlock,
) {

    @DeleteMapping("/api/v1/notes/blocks/{member}")
    fun remove(
        @AuthenticationPrincipal currentUserId: Long?,
        @PathVariable("member") blockedUserId: Long,
    ): ResponseEntity<Void> {
        removal.remove(currentUserId!!, blockedUserId)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build()
    }
}
