package com.pawbridge.backend.animal.internal.image.web;

import com.pawbridge.backend.animal.internal.image.infrastructure.AnimalImageService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Original ImageController routes and response maps; only the verified JWT member is trusted. */
@RestController
@RequestMapping("/api/v1/animals/images")
public final class AnimalImageController {
    private final AnimalImageService images;
    public AnimalImageController(AnimalImageService images) { this.images = images; }

    @PostMapping
    public ResponseEntity<Map<String, String>> uploadImage(@AuthenticationPrincipal Long verifiedUserId,
            @RequestParam("file") MultipartFile file) {
        String imageUrl = images.uploadImage(verifiedUserId, file);

        Map<String, String> response = new HashMap<>();
        response.put("imageUrl", imageUrl);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/multiple")
    public ResponseEntity<Map<String, Object>> uploadMultipleImages(@RequestParam("files") MultipartFile[] files) {
        List<String> imageUrls = images.uploadImages(files);

        Map<String, Object> response = new HashMap<>();
        response.put("imageUrls", imageUrls);
        response.put("count", imageUrls.size());

        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteImage(@AuthenticationPrincipal Long verifiedUserId,
            @RequestParam("imageUrl") String imageUrl) {
        images.deleteImage(verifiedUserId, imageUrl);

        Map<String, String> response = new HashMap<>();
        response.put("message", "이미지가 삭제되었습니다.");

        return ResponseEntity.ok(response);
    }
}
