package com.pawbridge.animalservice.lostsearch.gallery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "lost-gallery-feed.enabled", havingValue = "true")
public class LostGalleryFeedController {
    private final LostGalleryFeed feed;
    private final byte[] key;
    private final Semaphore permit = new Semaphore(1);

    public LostGalleryFeedController(LostGalleryFeed feed,
            @Value("${lost-gallery-feed.internal-api-key}") String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Gallery feed authentication is required");
        this.feed = feed;
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/internal/animals/lost-gallery", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> snapshot(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String supplied,
            @RequestHeader(value = "If-None-Match", required = false) String etag) {
        if (supplied == null || !MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Cache-Control", "no-store").build();
        if (!permit.tryAcquire()) return unavailable();
        try {
            var result = feed.snapshot(etag);
            return ResponseEntity.status(result.body() == null ? 304 : 200)
                    .header("Cache-Control", "no-store").eTag(result.etag()).body(result.body());
        } catch (Exception error) {
            // Do not expose signed URLs, storage credentials, SQL details or partial responses.
            return unavailable();
        } finally {
            permit.release();
        }
    }

    private ResponseEntity<byte[]> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", "no-store").header("Retry-After", "30").build();
    }

}
