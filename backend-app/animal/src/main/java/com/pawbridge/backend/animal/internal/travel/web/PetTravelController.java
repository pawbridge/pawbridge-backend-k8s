package com.pawbridge.backend.animal.internal.travel.web;

import com.pawbridge.backend.animal.internal.travel.application.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/places")
public class PetTravelController {
    private final PetTravel service;
    public PetTravelController(PetTravel service) { this.service = service; }

    @GetMapping("/regions")
    public PetTravelResponse.Regions regions() { return service.regions(); }

    @GetMapping
    public PetTravelResponse.Places places(@RequestParam(value = "areaCode", required = false) String areaCode,
                                          @RequestParam(value = "page", defaultValue = "0") int page) {
        return service.places(areaCode, page);
    }

    @GetMapping("/{contentId}")
    public PetTravelResponse.Detail detail(@PathVariable("contentId") String contentId) { return service.detail(contentId); }

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

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    public ResponseEntity<Map<String, String>> databaseFailure() {
        return failure(PetTravelException.unavailable());
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalidParameter() {
        return failure(new PetTravelException(PetTravelException.Code.INVALID_REQUEST));
    }
}
