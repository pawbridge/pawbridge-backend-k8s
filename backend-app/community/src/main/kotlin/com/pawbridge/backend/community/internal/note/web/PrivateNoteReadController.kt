package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.PrivateNoteView
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class PrivateNoteReadController(
    private val notes: ReadPrivateNote,
) {

    @GetMapping("/api/v1/notes/{id}")
    fun get(
        @AuthenticationPrincipal currentUserId: Long?,
        @PathVariable("id") noteId: UUID,
    ): ResponseEntity<Response<PrivateNoteView>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", notes.read(currentUserId!!, noteId)))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
