package com.pawbridge.animalservice.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Inclusive APMS happenDt range; unrelated to observation dates. */
public record IntakeDateRange(LocalDate from, LocalDate to) {
    public IntakeDateRange {
        if (from == null || to == null || from.getYear() < 1 || to.getYear() > 9999 || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) >= 366) {
            throw new IllegalArgumentException("접수일은 시작일과 종료일을 함께 지정하고 최대 366일 이내로 선택해주세요.");
        }
    }
}
