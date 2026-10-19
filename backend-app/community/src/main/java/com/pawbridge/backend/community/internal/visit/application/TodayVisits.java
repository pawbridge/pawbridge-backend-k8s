package com.pawbridge.backend.community.internal.visit.application;

import java.time.LocalDate;

public record TodayVisits(LocalDate date, long count) {
}
