package com.pawbridge.userservice.shelter;

import java.time.LocalDateTime;

public record ShelterApplicationResponse(Long id, Long userId, String shelterName,
        ShelterApplicationStatus status, LocalDateTime requestedAt, LocalDateTime reviewedAt,
        Long reviewedBy, String reason, String careRegNo) {
    public static ShelterApplicationResponse from(ShelterApplication a) {
        return new ShelterApplicationResponse(a.getId(), a.getUserId(), a.getShelterName(),
                a.getStatus(), a.getRequestedAt(), a.getReviewedAt(), a.getReviewedBy(),
                a.getStatus() == ShelterApplicationStatus.REJECTED ? a.getReviewNote() : null,
                a.getCareRegNo());
    }
}
