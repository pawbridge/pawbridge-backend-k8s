package com.pawbridge.backend.animal.internal.discovery.web;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AnimalDiscoveryController {
    private final AnimalDiscovery animals;
    public AnimalDiscoveryController(AnimalDiscovery animals) { this.animals = animals; }

    @GetMapping("/api/v1/animals")
    public AnimalPage search(@ModelAttribute AnimalSearchParameters parameters,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page result = animals.search(parameters.search(), new PageRequest(pageable.getPageNumber(),
                pageable.getPageSize(), pageable.getSort().stream()
                        .map(order -> new Order(order.getProperty(), order.isAscending())).toList(), pageable.isPaged()));
        return new AnimalPage(result.content().stream().map(AnimalSummary::from).toList(), result.number(),
                result.size(), result.totalElements(), result.totalPages(), result.first(), result.last());
    }

    @GetMapping("/api/v1/animals/{id}")
    public AnimalDetails detail(@PathVariable("id") Long id) { return AnimalDetails.from(animals.findById(id)); }

    @GetMapping("/api/v1/animals/apms/{apmsDesertionNo}")
    public AnimalDetails getAnimalByApmsNo(@PathVariable("apmsDesertionNo") String number) {
        return AnimalDetails.from(animals.findByApmsDesertionNo(number));
    }

    @GetMapping("/api/v1/animals/expiring-soon")
    public AnimalPage listExpiringSoon(@PageableDefault(size = 20, sort = "noticeEndDate",
            direction = Sort.Direction.ASC) Pageable pageable) {
        Page result = animals.findExpiringSoonAnimals(new PageRequest(pageable.getPageNumber(),
                pageable.getPageSize(), pageable.getSort().stream()
                        .map(order -> new Order(order.getProperty(), order.isAscending())).toList(), pageable.isPaged()));
        return new AnimalPage(result.content().stream().map(AnimalSummary::from).toList(), result.number(),
                result.size(), result.totalElements(), result.totalPages(), result.first(), result.last());
    }

    public record AnimalPage(List<AnimalSummary> content, int number, int size, long totalElements,
                             int totalPages, boolean first, boolean last) { }
}
