package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NoteBlockRemoveController {
    private final RemoveNoteBlock removal;

    public NoteBlockRemoveController(RemoveNoteBlock removal) {
        this.removal = removal;
    }

    @DeleteMapping("/api/v1/notes/blocks/{member}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Long currentUserId,
                                       @PathVariable("member") long blockedUserId) {
        removal.remove(currentUserId, blockedUserId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
}
