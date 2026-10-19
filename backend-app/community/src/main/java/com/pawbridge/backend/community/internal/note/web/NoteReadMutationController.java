package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NoteReadMutationController {
    private final MarkPrivateNoteRead mutation;

    public NoteReadMutationController(MarkPrivateNoteRead mutation) {
        this.mutation = mutation;
    }

    @PutMapping("/api/v1/notes/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal Long currentUserId,
                                     @PathVariable("id") UUID noteId) {
        mutation.read(currentUserId, noteId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
}
