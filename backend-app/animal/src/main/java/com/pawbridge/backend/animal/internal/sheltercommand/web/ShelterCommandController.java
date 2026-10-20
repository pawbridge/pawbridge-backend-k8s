package com.pawbridge.backend.animal.internal.sheltercommand.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shelters")
public final class ShelterCommandController {
    private final ShelterCommands shelters;
    public ShelterCommandController(ShelterCommands shelters) { this.shelters = shelters; }

    @PostMapping
    public ResponseEntity<Details> create(@AuthenticationPrincipal Long verifiedUserId,
            @Valid @RequestBody CreateShelterRequest request) {
        var command = new ShelterCommands.Create(request.getCareRegNo(), request.getName(), request.getPhone(),
                request.getAddress(), request.getOwnerName(), request.getOrganizationName(), request.getEmail(),
                request.getIntroduction(), request.getAdoptionProcedure(), request.getOperatingHours());
        return ResponseEntity.status(201).body(Details.from(shelters.create(verifiedUserId, command)));
    }
    @PutMapping("/{id}")
    public Details update(@AuthenticationPrincipal Long verifiedUserId, @PathVariable("id") Long id,
            @Valid @RequestBody UpdateShelterRequest request) {
        return Details.from(shelters.update(verifiedUserId, id, new ShelterCommands.Update(request.getPhone(),
                request.getEmail(), request.getIntroduction(), request.getAdoptionProcedure(), request.getOperatingHours())));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long verifiedUserId, @PathVariable("id") Long id) {
        shelters.delete(verifiedUserId, id);
        return ResponseEntity.noContent().build();
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Details(Long id, String careRegNo, String name, String phone, String address, String ownerName,
            String organizationName, String email, String introduction, String adoptionProcedure, String operatingHours,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        static Details from(ShelterCommands.Details value) {
            return new Details(value.id(), value.careRegNo(), value.name(), value.phone(), value.address(), value.ownerName(),
                    value.organizationName(), value.email(), value.introduction(), value.adoptionProcedure(), value.operatingHours(),
                    value.createdAt(), value.updatedAt());
        }
    }
}
