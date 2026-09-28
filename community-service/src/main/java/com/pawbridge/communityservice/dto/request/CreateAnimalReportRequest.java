package com.pawbridge.communityservice.dto.request;

import com.pawbridge.communityservice.domain.entity.AnimalReport;

import java.time.LocalDate;

public record CreateAnimalReportRequest(
        AnimalReport.Kind kind,
        LocalDate occurredOn,
        String approximateTime,
        String region,
        String landmark,
        String species,
        String animalName,
        String coatColor,
        String animalSize,
        String distinguishingFeatures,
        String direction,
        String description
) {
}
