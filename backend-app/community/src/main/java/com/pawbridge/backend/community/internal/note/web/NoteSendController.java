package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.SendPrivateNote;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.SendNote;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NoteSendController {
    private final SendPrivateNote sending;

    public NoteSendController(SendPrivateNote sending) {
        this.sending = sending;
    }

    @PostMapping("/api/v1/notes")
    public ResponseEntity<Response<Receipt>> send(@AuthenticationPrincipal Long currentUserId,
                                                @RequestBody SendNote request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Response<>(200, "요청이 성공적으로 처리되었습니다.", sending.send(currentUserId, request)));
    }

    public record Response<T>(int code, String message, T data) { }
}
