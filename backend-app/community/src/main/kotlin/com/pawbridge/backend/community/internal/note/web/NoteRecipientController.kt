package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient
import com.pawbridge.backend.user.api.ContactMember
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteRecipientController(
    private val recipients: ReadNoteRecipient,
) {

    @GetMapping("/api/v1/notes/recipients/{member}")
    fun recipient(
        @AuthenticationPrincipal currentUserId: Long?,
        @PathVariable("member") recipientId: Long,
    ): ResponseEntity<Response<ContactMember>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", recipients.read(currentUserId!!, recipientId)))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
