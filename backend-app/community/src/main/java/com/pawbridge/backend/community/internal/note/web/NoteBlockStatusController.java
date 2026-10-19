package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.BlockedAuthors;
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NoteBlockStatusController {
    private final ReadBlockedAuthors blockedAuthors;

    public NoteBlockStatusController(ReadBlockedAuthors blockedAuthors) {
        this.blockedAuthors = blockedAuthors;
    }

    @GetMapping("/api/v1/notes/blocks/status")
    public ResponseEntity<Response<BlockedAuthors>> blockedAuthors(@AuthenticationPrincipal Long currentUserId,
                                                                  @RequestParam("memberIds") List<Long> memberIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Response<>(200, "요청이 성공적으로 처리되었습니다.",
                        blockedAuthors.read(currentUserId, memberIds)));
    }

    public record Response<T>(int code, String message, T data) {
    }
}
