package com.pawbridge.animalservice.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Only recorded observations are returned. An absent date is not a zero count. */
public record ShelterObservationResponse(LocalDate date, OffsetDateTime observedAt, long protectedCount) {}
