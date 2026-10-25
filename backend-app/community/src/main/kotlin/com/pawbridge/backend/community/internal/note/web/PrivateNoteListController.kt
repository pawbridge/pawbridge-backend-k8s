package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.ListPrivateNotes
import com.pawbridge.backend.community.internal.note.application.PrivateNotePage
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class PrivateNoteListController(
    private val notes: ListPrivateNotes,
) {

    @GetMapping("/api/v1/notes")
    fun get(
        @AuthenticationPrincipal currentUserId: Long?,
        @RequestParam(name = "box", defaultValue = "INBOX") box: String,
        @RequestParam(name = "favorites", defaultValue = "false") favorites: Boolean,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
    ): ResponseEntity<Response<PrivateNotePage>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", notes.read(currentUserId!!, box, favorites, page)))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
