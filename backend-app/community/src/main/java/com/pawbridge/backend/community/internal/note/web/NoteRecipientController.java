package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient;
import com.pawbridge.backend.user.api.ContactMember;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NoteRecipientController {
    private final ReadNoteRecipient recipients;

    public NoteRecipientController(ReadNoteRecipient recipients) {
        this.recipients = recipients;
    }

    @GetMapping("/api/v1/notes/recipients/{member}")
    public ResponseEntity<Response<ContactMember>> recipient(@AuthenticationPrincipal Long currentUserId,
                                                            @PathVariable("member") long recipientId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Response<>(200, "요청이 성공적으로 처리되었습니다.",
                        recipients.read(currentUserId, recipientId)));
    }

    public record Response<T>(int code, String message, T data) {
    }
}
