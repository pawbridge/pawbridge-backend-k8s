package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public final class NoteStreamController {
    private final RequireActiveNoteStream active;
    private final PrivateNoteStreamRegistry streams;

    public NoteStreamController(RequireActiveNoteStream active, PrivateNoteStreamRegistry streams) {
        this.active = active;
        this.streams = streams;
    }

    @GetMapping(value = "/api/v1/notes/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> open(@AuthenticationPrincipal Long currentUserId,
                                         Authentication authentication,
                                         @RequestParam(name = "clientId", required = false) UUID clientId) {
        // Only the verified authentication carries expiry; request headers never supply it.
        if (authentication == null || !(authentication.getDetails() instanceof Instant expiresAt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        active.requireActive(currentUserId);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no")
                .body(clientId == null ? streams.open(currentUserId, expiresAt)
                        : streams.open(currentUserId, expiresAt, clientId));
    }
}
