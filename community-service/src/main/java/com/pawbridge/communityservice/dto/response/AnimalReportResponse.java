package com.pawbridge.communityservice.dto.response;

import com.pawbridge.communityservice.domain.entity.AnimalReport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AnimalReportResponse(
        Long reportId,
        Long authorId,
        String authorNickname,
        String title,
        String description,
        List<String> imageUrls,
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AnimalReportResponse from(AnimalReport report, String authorNickname) {
        String label = report.getKind() == AnimalReport.Kind.MISSING ? "실종" : "목격 제보";
        return new AnimalReportResponse(report.getReportId(), report.getAuthorId(), authorNickname,
                label + " · " + report.getRegion() + " · " + report.getSpecies(),
                report.getDescription(), List.copyOf(report.getImageUrls()), report.getKind(),
                report.getOccurredOn(), report.getApproximateTime(), report.getRegion(),
                report.getLandmark(), report.getSpecies(), report.getAnimalName(),
                report.getCoatColor(), report.getAnimalSize(), report.getDistinguishingFeatures(),
                report.getDirection(), report.getCreatedAt(), report.getUpdatedAt());
    }
}
