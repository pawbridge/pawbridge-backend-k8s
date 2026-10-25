package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.NoteNotifications
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteNotificationsController(
    private val notes: ReadNoteNotifications,
) {

    @GetMapping("/api/v1/notes/notifications")
    fun notifications(
        @AuthenticationPrincipal currentUserId: Long?,
        @RequestParam(name = "cursor", required = false) cursor: UUID?,
    ): ResponseEntity<Response<NoteNotifications>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", notes.read(currentUserId!!, cursor)))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
