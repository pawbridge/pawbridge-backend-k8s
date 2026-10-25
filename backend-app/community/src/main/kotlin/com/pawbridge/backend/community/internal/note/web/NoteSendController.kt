package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.SendPrivateNote
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.SendNote
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class NoteSendController(
    private val sending: SendPrivateNote,
) {

    @PostMapping("/api/v1/notes")
    fun send(
        @AuthenticationPrincipal currentUserId: Long?,
        @RequestBody request: SendNote,
    ): ResponseEntity<Response<Receipt>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response(200, "요청이 성공적으로 처리되었습니다.", sending.send(currentUserId!!, request)))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
