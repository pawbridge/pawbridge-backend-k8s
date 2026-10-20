package com.pawbridge.backend.animal.internal.shelter.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import java.time.LocalDateTime;

final class ShelterResponses {
    private ShelterResponses() { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Summary(Long id, String careRegNo, String name, String phone, String address,
                   String organizationName, LocalDateTime createdAt) {
        static Summary from(ShelterReads.Summary value) {
            return new Summary(value.id(), value.careRegNo(), value.name(), value.phone(), value.address(),
                    value.organizationName(), value.createdAt());
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Details(Long id, String careRegNo, String name, String phone, String address, String ownerName,
                   String organizationName, String email, String introduction, String adoptionProcedure,
                   String operatingHours, LocalDateTime createdAt, LocalDateTime updatedAt, PublicInformation publicInformation) {
        static Details from(ShelterReads.Details value) {
            return new Details(value.id(), value.careRegNo(), value.name(), value.phone(), value.address(), value.ownerName(),
                    value.organizationName(), value.email(), value.introduction(), value.adoptionProcedure(), value.operatingHours(),
                    value.createdAt(), value.updatedAt(), value.publicInformation() == null ? null : PublicInformation.from(value.publicInformation()));
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PublicInformation(String source, String phone, Double latitude, Double longitude,
            String weekdayOpen, String weekdayClose, String weekendOpen, String weekendClose,
            String closedDays, String sourceUpdatedDate, LocalDateTime collectedAt) {
        static PublicInformation from(ShelterReads.PublicInformation value) {
            return new PublicInformation(value.source(), value.phone(), value.latitude(), value.longitude(), value.weekdayOpen(),
                    value.weekdayClose(), value.weekendOpen(), value.weekendClose(), value.closedDays(), value.sourceUpdatedDate(), value.collectedAt());
        }
    }
}
