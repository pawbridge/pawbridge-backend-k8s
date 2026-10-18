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
        String description,
        String province,
        String district,
        AnimalReport.AnimalType animalType
) {
    public CreateAnimalReportRequest(AnimalReport.Kind kind, LocalDate occurredOn,
                                    String approximateTime, String region, String landmark, String species,
                                    String animalName, String coatColor, String animalSize,
                                    String distinguishingFeatures, String direction, String description) {
        this(kind, occurredOn, approximateTime, region, landmark, species, animalName, coatColor,
                animalSize, distinguishingFeatures, direction, description, null, null, null);
    }
}
