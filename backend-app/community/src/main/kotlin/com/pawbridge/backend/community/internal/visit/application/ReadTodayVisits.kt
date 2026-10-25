package com.pawbridge.backend.community.internal.visit.application

import java.time.Clock
import java.time.ZoneId

class ReadTodayVisits(
    private val reader: VisitCountReader,
    private val clock: Clock,
) {

    fun read(): TodayVisits {
        val day = clock.instant().atZone(VISIT_ZONE).toLocalDate()
        return TodayVisits(day, reader.count(day))
    }

    private companion object {
        private val VISIT_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
