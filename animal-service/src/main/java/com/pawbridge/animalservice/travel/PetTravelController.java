package com.pawbridge.animalservice.travel;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/places")
public class PetTravelController {
    private final PetTravelService service;
    public PetTravelController(PetTravelService service) { this.service = service; }

    @GetMapping("/regions")
    public PetTravelResponse.Regions regions() { return service.regions(); }

    @GetMapping
    public PetTravelResponse.Places places(@RequestParam(required = false) String areaCode) {
        return service.places(areaCode);
    }

    @GetMapping("/{contentId}")
    public PetTravelResponse.Detail detail(@PathVariable String contentId) { return service.detail(contentId); }

    @ExceptionHandler(PetTravelException.class)
    public ResponseEntity<Map<String, String>> failure(PetTravelException exception) {
        var status = switch (exception.getCode()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("code", "PET_TRAVEL_" + exception.getCode().name()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, String>> databaseFailure() {
        return failure(PetTravelException.unavailable());
    }
}
