package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.BlockPage;
import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NoteBlockListController {
    private final ListNoteBlocks blocks;

    public NoteBlockListController(ListNoteBlocks blocks) {
        this.blocks = blocks;
    }

    @GetMapping("/api/v1/notes/blocks")
    public ResponseEntity<Response<BlockPage>> blocks(@AuthenticationPrincipal Long currentUserId,
                                                     @RequestParam(name = "page", defaultValue = "0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Response<>(200, "요청이 성공적으로 처리되었습니다.", blocks.read(currentUserId, page)));
    }

    public record Response<T>(int code, String message, T data) {
    }
}
