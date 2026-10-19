package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.ListPrivateNotes;
import com.pawbridge.backend.community.internal.note.application.PrivateNotePage;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PrivateNoteListController {
    private final ListPrivateNotes notes;

    public PrivateNoteListController(ListPrivateNotes notes) {
        this.notes = notes;
    }

    @GetMapping("/api/v1/notes")
    public ResponseEntity<Response<PrivateNotePage>> get(@AuthenticationPrincipal Long currentUserId,
                                                       @RequestParam(name = "box", defaultValue = "INBOX") String box,
                                                       @RequestParam(name = "favorites", defaultValue = "false") boolean favorites,
                                                       @RequestParam(name = "page", defaultValue = "0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Response<>(200, "요청이 성공적으로 처리되었습니다.", notes.read(currentUserId, box, favorites, page)));
    }

    public record Response<T>(int code, String message, T data) {
    }
}
