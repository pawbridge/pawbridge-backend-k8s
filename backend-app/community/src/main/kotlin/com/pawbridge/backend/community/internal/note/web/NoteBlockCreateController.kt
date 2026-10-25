package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.CreateNoteBlock
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteBlockCreateController(
    private val creation: CreateNoteBlock,
) {

    @PutMapping("/api/v1/notes/blocks/{member}")
    fun create(
        @AuthenticationPrincipal currentUserId: Long?,
        @PathVariable("member") blockedUserId: Long,
    ): ResponseEntity<Void> {
        creation.create(currentUserId!!, blockedUserId)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build()
    }
}
