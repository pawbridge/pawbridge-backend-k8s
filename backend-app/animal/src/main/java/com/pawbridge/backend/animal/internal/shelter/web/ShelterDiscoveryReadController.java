package com.pawbridge.backend.animal.internal.shelter.web;

import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Discovery;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Observation;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Parent registers this PostgreSQL-only controller with the selected Animal read backend. */
@RestController
public final class ShelterDiscoveryReadController {
    private final ShelterReads shelters;
    public ShelterDiscoveryReadController(ShelterReads shelters) { this.shelters = shelters; }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> invalidParameter() { return ResponseEntity.badRequest().build(); }

    @GetMapping("/api/v1/shelters/discovery")
    public Page<Discovery> discover(@RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "address", defaultValue = "") String address,
            @RequestParam(name = "intakeFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "intakeTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "12") int size) {
        if (from == null && to == null) {
            to = LocalDate.now(ZoneId.of("Asia/Seoul"));
            from = to.minusDays(29);
        }
        var result = shelters.discover(keyword, address, from, to, page, size);
        return new PageImpl<>(result.content(), PageRequest.of(page, size), result.totalElements());
    }

    @GetMapping("/api/v1/shelters/{id}/observations")
    public List<Observation> observations(@PathVariable("id") long id,
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return shelters.observations(id, from, to);
    }
}
