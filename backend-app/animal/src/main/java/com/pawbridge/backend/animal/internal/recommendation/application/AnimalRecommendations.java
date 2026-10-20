package com.pawbridge.backend.animal.internal.recommendation.application;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Similarity order is supplied by Python; current relational rows determine visibility. */
public interface AnimalRecommendations {
    List<Card> recommend(long animalId);

    record Card(Long id, String apmsNoticeNo, Species species, String breed, Gender gender,
                Integer birthYear, Integer age, String weight, String color, LocalDate happenDate,
                String specialMark, AnimalStatus status, LocalDate noticeEndDate, String imageUrl,
                Integer favoriteCount, Long shelterId, String shelterName, LocalDateTime createdAt) { }
}
