package com.pawbridge.backend.store.internal.catalogwrite.web;

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/images")
public final class ProductImageController {
    private final S3ProductImages images;
    public ProductImageController(S3ProductImages images){this.images=images;}
    @PostMapping public ResponseEntity<Map<String,String>> uploadImage(@RequestParam("file") MultipartFile file){
        return ResponseEntity.ok(Map.of("imageUrl",images.uploadImage(file)));
    }
}
