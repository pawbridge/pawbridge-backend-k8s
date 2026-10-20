package com.pawbridge.backend.animal.internal.command.web;

import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalStatusRequest;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalDescriptionRequest;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/animals")
public final class AnimalCommandController {
    private final AnimalCommandService animals;
    public AnimalCommandController(AnimalCommandService animals) { this.animals = animals; }

    @PostMapping
    public ResponseEntity<AnimalDetailResponse> create(@AuthenticationPrincipal Long verifiedUserId,
            @Valid @RequestBody CreateAnimalRequest request) {
        return ResponseEntity.status(201).body(animals.create(verifiedUserId, request));
    }

    @PatchMapping("/{id}/status")
    public AnimalDetailResponse updateStatus(@AuthenticationPrincipal Long verifiedUserId,
            @PathVariable("id") Long id, @Valid @RequestBody UpdateAnimalStatusRequest request) {
        return animals.updateStatus(verifiedUserId, id, request);
    }

    @PatchMapping("/{id}/description")
    public AnimalDetailResponse updateDescription(@AuthenticationPrincipal Long verifiedUserId,
            @PathVariable("id") Long id, @Valid @RequestBody UpdateAnimalDescriptionRequest request) {
        return animals.updateDescription(verifiedUserId, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long verifiedUserId, @PathVariable("id") Long id) {
        animals.delete(verifiedUserId, id);
        return ResponseEntity.noContent().build();
    }
}
