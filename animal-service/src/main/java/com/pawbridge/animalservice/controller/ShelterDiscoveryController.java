package com.pawbridge.animalservice.controller;

import com.pawbridge.animalservice.dto.response.ShelterDiscoveryResponse;
import com.pawbridge.animalservice.dto.response.ShelterObservationResponse;
import com.pawbridge.animalservice.service.ShelterDiscoveryService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shelters")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pawbridge.animal-query", name = "backend", havingValue = "postgresql")
public class ShelterDiscoveryController {
    private final ShelterDiscoveryService service;

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<Void> invalidParameter() {
        return org.springframework.http.ResponseEntity.badRequest().build();
    }

    @GetMapping("/discovery")
    public Page<ShelterDiscoveryResponse> discover(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String address,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate intakeFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate intakeTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        if (intakeFrom == null && intakeTo == null) {
            intakeTo = LocalDate.now(ZoneId.of("Asia/Seoul"));
            intakeFrom = intakeTo.minusDays(29);
        }
        return service.discover(keyword, address, intakeFrom, intakeTo, page, size);
    }

    @GetMapping("/{id}/observations")
    public List<ShelterObservationResponse> observations(
            @PathVariable long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.observations(id, from, to);
    }
}
