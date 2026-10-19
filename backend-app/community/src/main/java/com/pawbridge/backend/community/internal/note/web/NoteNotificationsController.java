package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.NoteNotifications;
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NoteNotificationsController {
    private final ReadNoteNotifications notes;

    public NoteNotificationsController(ReadNoteNotifications notes) {
        this.notes = notes;
    }

    @GetMapping("/api/v1/notes/notifications")
    public ResponseEntity<Response<NoteNotifications>> notifications(@AuthenticationPrincipal Long currentUserId,
            @RequestParam(name = "cursor", required = false) UUID cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Response<>(200, "요청이 성공적으로 처리되었습니다.", notes.read(currentUserId, cursor)));
    }

    public record Response<T>(int code, String message, T data) {
    }
}
