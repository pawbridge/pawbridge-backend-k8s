package com.pawbridge.userservice.shelter;

import java.time.LocalDate;

public record DailyShelterApplicationStats(LocalDate date, long count) {}
