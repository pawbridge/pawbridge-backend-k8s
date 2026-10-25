package com.pawbridge.backend.community.internal.visit.application

import java.time.Instant
import java.time.LocalDate

interface VisitCollectionStore {

    fun start(instant: Instant)

    fun startedAt(): Instant

    fun lockDay(day: LocalDate, exclusive: Boolean)

    fun count(day: LocalDate, hash: String)

    fun today(day: LocalDate): Long

    fun period(start: LocalDate, end: LocalDate): List<VisitCollection.Day>

    fun expiredDays(today: LocalDate): List<LocalDate>

    fun deleteDay(day: LocalDate)
}
