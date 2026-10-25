package com.pawbridge.backend.community.internal.note.web

import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
class NoteStreamController(
    private val active: RequireActiveNoteStream,
    private val streams: PrivateNoteStreamRegistry,
) {

    @GetMapping(value = ["/api/v1/notes/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun open(
        @AuthenticationPrincipal currentUserId: Long?,
        authentication: Authentication?,
        @RequestParam(name = "clientId", required = false) clientId: UUID?,
    ): ResponseEntity<SseEmitter> {
        // Only the verified authentication carries expiry; request headers never supply it.
        val expiresAt = authentication?.details
        if (authentication == null || expiresAt !is Instant) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.")
        }
        active.requireActive(currentUserId!!)
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .header("X-Accel-Buffering", "no")
            .body(
                if (clientId == null) {
                    streams.open(currentUserId, expiresAt)
                } else {
                    streams.open(currentUserId, expiresAt, clientId)
                },
            )
    }
}
