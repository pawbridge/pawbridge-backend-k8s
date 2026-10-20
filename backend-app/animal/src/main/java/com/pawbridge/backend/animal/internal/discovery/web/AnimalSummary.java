package com.pawbridge.backend.animal.internal.discovery.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnimalSummary(Long id, String apmsNoticeNo, Species species, String breed, Gender gender,
        Integer birthYear, Integer age, String specialMark, AnimalStatus status, LocalDate noticeEndDate,
        String imageUrl, Integer favoriteCount, Long shelterId, String shelterName, LocalDateTime createdAt) {
    static AnimalSummary from(Summary animal) {
        return new AnimalSummary(animal.id(), animal.apmsNoticeNo(), animal.species(), animal.breed(), animal.gender(),
                animal.birthYear(), animal.age(), animal.specialMark(), animal.status(), animal.noticeEndDate(),
                animal.imageUrl(), animal.favoriteCount(), animal.shelterId(), animal.shelterName(), animal.createdAt());
    }
}
