package com.pawbridge.backend.store.internal.wishlist.web;

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Only explicitly assembled by Root with authentication and default-off activation gates. */
@RestController
public final class WishlistCommandController {
    public record AddRequest(Long skuId) { }
    private final WishlistCommands commands;
    public WishlistCommandController(WishlistCommands commands) { this.commands=commands; }

    @PostMapping("/api/v1/wishlists")
    public ResponseEntity<WishlistCommands.Added> add(@AuthenticationPrincipal Long current,@RequestBody AddRequest request) {
        return ResponseEntity.status(201).body(commands.add(current(current),request.skuId()));
    }

    @DeleteMapping("/api/v1/wishlists")
    public ResponseEntity<Void> removeBySku(@AuthenticationPrincipal Long current,@RequestParam("skuId") long skuId) {
        commands.removeByMemberAndSku(current(current),skuId);
        return ResponseEntity.noContent().build();
    }

    // No DELETE /{wishlistId}: Root must accept the owner-scoped replacement before exposing it.
    private static long current(Long member) {
        if(member==null) throw new AuthenticationCredentialsNotFoundException("An authenticated member is required");
        return member;
    }
}
