package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.CreateNoteBlock;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NoteBlockCreateController {
    private final CreateNoteBlock creation;

    public NoteBlockCreateController(CreateNoteBlock creation) {
        this.creation = creation;
    }

    @PutMapping("/api/v1/notes/blocks/{member}")
    public ResponseEntity<Void> create(@AuthenticationPrincipal Long currentUserId,
                                       @PathVariable("member") long blockedUserId) {
        creation.create(currentUserId, blockedUserId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
}
