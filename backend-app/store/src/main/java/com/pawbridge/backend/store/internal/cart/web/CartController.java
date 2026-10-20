package com.pawbridge.backend.store.internal.cart.web;

import com.pawbridge.backend.store.internal.cart.application.Carts;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/carts")
public final class CartController {
    private final Carts carts;
    public CartController(Carts carts) {this.carts=carts;}
    @GetMapping public ResponseEntity<List<Carts.Item>> get(@AuthenticationPrincipal Long user) {return ResponseEntity.ok(carts.get(current(user)));}
    @PostMapping("/items") public ResponseEntity<Void> add(@AuthenticationPrincipal Long user,@RequestBody CartAddRequest request) {
        carts.add(current(user),request.getSkuId(),request.getQuantity());return ResponseEntity.ok().build();
    }
    @PatchMapping("/items/{skuId}") public ResponseEntity<Void> update(@AuthenticationPrincipal Long user,@PathVariable("skuId") long skuId,@RequestParam("quantity") int quantity) {
        carts.update(current(user),skuId,quantity);return ResponseEntity.ok().build();
    }
    @DeleteMapping("/items/{skuId}") public ResponseEntity<Void> remove(@AuthenticationPrincipal Long user,@PathVariable("skuId") long skuId) {
        carts.remove(current(user),skuId);return ResponseEntity.ok().build();
    }
    @DeleteMapping public ResponseEntity<Void> clear(@AuthenticationPrincipal Long user) {carts.clear(current(user));return ResponseEntity.ok().build();}
    private static long current(Long user) {if(user==null)throw new AuthenticationCredentialsNotFoundException("An authenticated member is required");return user;}
}
