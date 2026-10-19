package com.pawbridge.backend.community.internal.note.web;

import com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NoteFavoriteController {
    private final SetPrivateNoteFavorite mutation;

    public NoteFavoriteController(SetPrivateNoteFavorite mutation) {
        this.mutation = mutation;
    }

    @PutMapping("/api/v1/notes/{id}/favorite")
    public ResponseEntity<Void> set(@AuthenticationPrincipal Long currentUserId,
                                    @PathVariable("id") UUID noteId,
                                    @RequestBody FavoriteRequest request) {
        mutation.set(currentUserId, noteId, request.favorite());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    public record FavoriteRequest(boolean favorite) { }
}
