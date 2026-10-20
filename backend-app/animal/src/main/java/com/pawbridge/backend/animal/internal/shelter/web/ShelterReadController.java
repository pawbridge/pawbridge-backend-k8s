package com.pawbridge.backend.animal.internal.shelter.web;

import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ShelterReadController {
    private final ShelterReads shelters;
    public ShelterReadController(ShelterReads shelters) { this.shelters = shelters; }

    @GetMapping("/api/v1/shelters")
    public Page<ShelterResponses.Summary> list(@RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "address", required = false) String address,
            @RequestParam(name = "organizationName", required = false) String organizationName,
            @RequestParam(name = "keyword", required = false) String keyword,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        var result = shelters.search(new ShelterReads.Search(name, address, organizationName, keyword),
                new ShelterReads.PageRequest(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().stream()
                        .map(order -> new ShelterReads.Order(order.getProperty(), order.isAscending(), order.isIgnoreCase())).toList()));
        return new PageImpl<>(result.content().stream().map(ShelterResponses.Summary::from).toList(), pageable, result.totalElements());
    }

    @GetMapping("/api/v1/shelters/{id}")
    public ShelterResponses.Details detail(@PathVariable("id") Long id) { return ShelterResponses.Details.from(shelters.findById(id)); }

    @GetMapping("/api/v1/shelters/by-care-reg-no/{careRegNo}")
    public ShelterResponses.Details byCareRegNo(@PathVariable("careRegNo") String careRegNo) {
        return ShelterResponses.Details.from(shelters.findByCareRegNo(careRegNo));
    }

    @GetMapping("/api/v1/shelters/count")
    public long count() { return shelters.count(); }
}
