package com.pawbridge.backend.animal.internal.discovery.web;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

public record AnimalSearchParameters(Species species, String breed, Gender gender, NeuterStatus neuterStatus,
        AnimalStatus status, Integer minAge, Integer maxAge, String region, String city, String keyword,
        String noticeNo, Long shelterId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate intakeFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate intakeTo) {
    Search search() {
        return new Search(species, breed, gender, neuterStatus, status, minAge, maxAge, region, city,
                keyword, noticeNo, shelterId, intakeFrom, intakeTo);
    }
}
