package com.pawbridge.backend.community.internal.visit.application

import java.time.Instant
import java.time.LocalDate

interface VisitCollection {

    fun start()

    fun count(cookie: String?): TodayVisits

    fun period(start: LocalDate?, end: LocalDate?): Period

    fun cleanup()

    @JvmRecord
    data class Day(val date: LocalDate, val count: Long)

    @JvmRecord
    data class Period(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val today: LocalDate,
        val collectionStartedAt: Instant,
        val daily: List<Day>,
    )
}
