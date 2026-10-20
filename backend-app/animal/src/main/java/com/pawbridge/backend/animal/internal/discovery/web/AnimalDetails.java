package com.pawbridge.backend.animal.internal.discovery.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnimalDetails(Long id, String apmsNoticeNo, Species species, String breed, Gender gender,
        NeuterStatus neuterStatus, Integer birthYear, Integer age, String weight, String color,
        String specialMark, AnimalStatus status, LocalDate noticeStartDate, LocalDate noticeEndDate,
        String happenPlace, LocalDate happenDate, String imageUrl, String imageUrl2, String description,
        Integer favoriteCount, Long shelterId, String shelterName, String apmsDesertionNo,
        String apmsProcessState, LocalDateTime apmsUpdatedAt, ApiSource apiSource,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    static AnimalDetails from(Details animal) {
        return new AnimalDetails(animal.id(), animal.apmsNoticeNo(), animal.species(), animal.breed(), animal.gender(),
                animal.neuterStatus(), animal.birthYear(), animal.age(), animal.weight(), animal.color(),
                animal.specialMark(), animal.status(), animal.noticeStartDate(), animal.noticeEndDate(),
                animal.happenPlace(), animal.happenDate(), animal.imageUrl(), animal.imageUrl2(), animal.description(),
                animal.favoriteCount(), animal.shelterId(), animal.shelterName(), animal.apmsDesertionNo(),
                animal.apmsProcessState(), animal.apmsUpdatedAt(), animal.apiSource(), animal.createdAt(), animal.updatedAt());
    }
}
