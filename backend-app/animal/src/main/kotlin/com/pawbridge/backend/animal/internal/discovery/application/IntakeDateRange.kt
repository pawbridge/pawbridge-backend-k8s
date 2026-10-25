package com.pawbridge.backend.animal.internal.discovery.application

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Inclusive APMS happenDt range; unrelated to observation dates. */
@JvmRecord
data class IntakeDateRange(
    val from: LocalDate?,
    val to: LocalDate?,
) {
    init {
        if (from == null ||
            to == null ||
            from.year < 1 ||
            to.year > 9999 ||
            from.isAfter(to) ||
            ChronoUnit.DAYS.between(from, to) >= 366
        ) {
            throw IllegalArgumentException("접수일은 시작일과 종료일을 함께 지정하고 최대 366일 이내로 선택해주세요.")
        }
    }
}
