package com.pawbridge.backend.animal.internal.gallery.web;

import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed;
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Callable;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/animals/lost-gallery/snapshots")
public class LostGalleryPagesController {
    private final LostGallerySnapshots snapshots;
    private final byte[] key;
    private final Semaphore permit = new Semaphore(1);
    public LostGalleryPagesController(LostGallerySnapshots snapshots, String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Gallery authentication is required");
        this.snapshots = snapshots; this.key = key.getBytes(StandardCharsets.UTF_8);
    }
    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value="X-Internal-Api-Key", required=false) String supplied,
                                     @RequestHeader(value="If-None-Match", required=false) String etag,
                                     @RequestHeader(value="X-Gallery-Request-Id", required=false) String requestId) {
        return handle(supplied, () -> {
            var value = snapshots.create(requestId); String tag = "\"" + value.snapshotSha256() + "\"";
            if (tag.equals(etag)) { snapshots.release(value.snapshotId()); return ResponseEntity.status(304).eTag(tag).build(); }
            return ResponseEntity.ok().eTag(tag).body(value);
        });
    }
    @GetMapping("/{id}")
    public ResponseEntity<?> describe(@PathVariable("id") String id, @RequestHeader(value="X-Internal-Api-Key", required=false) String supplied) {
        return handle(supplied, () -> ResponseEntity.ok(snapshots.describe(id)));
    }
    @GetMapping(value="/{id}/pages", produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> page(@PathVariable("id") String id, @RequestParam("cursor") String cursor,
                                  @RequestParam(value="limit", defaultValue="500") int limit,
                                  @RequestHeader(value="X-Internal-Api-Key", required=false) String supplied) {
        return handle(supplied, () -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(snapshots.page(id,cursor,limit)));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> release(@PathVariable("id") String id, @RequestHeader(value="X-Internal-Api-Key", required=false) String supplied) {
        return handle(supplied, () -> { snapshots.release(id); return ResponseEntity.noContent().build(); });
    }
    private ResponseEntity<?> handle(String supplied, Callable<ResponseEntity<?>> operation) {
        if (supplied == null || !MessageDigest.isEqual(key,supplied.getBytes(StandardCharsets.UTF_8))) return ResponseEntity.status(401).build();
        if (!permit.tryAcquire()) return unavailable();
        try {
            var response=operation.call();
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header("Cache-Control","no-store").body(response.getBody());
        } catch (LostGallerySnapshots.Gone error) { return ResponseEntity.status(410).header("Cache-Control","no-store").build(); }
        catch (LostGallerySnapshots.BadCursor error) { return ResponseEntity.badRequest().header("Cache-Control","no-store").build(); }
        catch (Exception error) { return unavailable(); }
        finally { permit.release(); }
    }
    private ResponseEntity<?> unavailable() { return ResponseEntity.status(503).header("Cache-Control","no-store").header("Retry-After","30").build(); }
}
