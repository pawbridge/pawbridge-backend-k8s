package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.BlockedAuthors
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteBlockStatusController(
    private val blockedAuthors: ReadBlockedAuthors,
) {

    @GetMapping("/api/v1/notes/blocks/status")
    fun blockedAuthors(
        @AuthenticationPrincipal currentUserId: Long?,
        @RequestParam("memberIds") memberIds: List<Long>,
    ): ResponseEntity<Response<BlockedAuthors>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", blockedAuthors.read(currentUserId!!, memberIds)))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
