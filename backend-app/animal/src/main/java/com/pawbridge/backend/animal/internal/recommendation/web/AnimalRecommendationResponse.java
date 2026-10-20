package com.pawbridge.backend.animal.internal.recommendation.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations.Card;
import java.time.LocalDate;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnimalRecommendationResponse(Long id, String apmsNoticeNo, Species species, String breed,
        Gender gender, Integer birthYear, Integer age, String weight, String color, LocalDate happenDate,
        String specialMark, AnimalStatus status, LocalDate noticeEndDate, String imageUrl,
        Integer favoriteCount, Long shelterId, String shelterName, LocalDateTime createdAt) {
    static AnimalRecommendationResponse from(Card card) {
        return new AnimalRecommendationResponse(card.id(), card.apmsNoticeNo(), card.species(), card.breed(),
                card.gender(), card.birthYear(), card.age(), card.weight(), card.color(), card.happenDate(),
                card.specialMark(), card.status(), card.noticeEndDate(), card.imageUrl(), card.favoriteCount(),
                card.shelterId(), card.shelterName(), card.createdAt());
    }
}
