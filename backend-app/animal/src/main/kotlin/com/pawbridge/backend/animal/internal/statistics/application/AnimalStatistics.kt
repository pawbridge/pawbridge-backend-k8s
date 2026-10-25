package com.pawbridge.backend.animal.internal.statistics.application

import java.time.LocalDate

/** Selected public/admin reads; counts describe retained records rather than lifecycle events. */
interface AnimalStatistics {
    fun today(): Today

    fun status(startDate: LocalDate?, endDate: LocalDate?): List<StatusCount>

    fun regional(startDate: LocalDate?, endDate: LocalDate?): List<RegionalCount>

    fun dailyAnimals(startDate: LocalDate?, endDate: LocalDate?): List<DailyCount>

    fun intakeTrend(startDate: LocalDate?, endDate: LocalDate?): IntakeTrend

    @JvmRecord
    data class Today(
        val date: LocalDate?,
        val rescuedToday: Long?,
        val adoptedToday: Long?,
    )

    @JvmRecord
    data class StatusCount(
        val status: Status?,
        val count: Long?,
    ) {
        fun label(): String? = status?.label()
    }

    @JvmRecord
    data class RegionalCount(
        val region: String?,
        val count: Long?,
    )

    @JvmRecord
    data class DailyCount(
        val date: LocalDate?,
        val count: Long?,
    )

    @JvmRecord
    data class IntakeTrend(
        val startDate: LocalDate?,
        val endDate: LocalDate?,
        val daily: List<DailyCount>?,
        val previousDayCount: Long,
    )

    /** Original AnimalStatus names/descriptions only; write-side APMS parsing is not imported. */
    enum class Status(private val label: String) {
        NOTICE("공고중"),
        PROTECT("보호중"),
        ADOPTION_PENDING("입양대기중"),
        ADOPTED("종료(입양)"),
        EUTHANIZED("종료(안락사)"),
        NATURAL_DEATH("종료(자연사)"),
        RETURNED("종료(반환)"),
        DONATED("종료(기증)"),
        RELEASED("종료(방사)"),
        ESCAPED("탈출"),
        UNKNOWN("미상"),
        ;

        fun label(): String = label
    }
}
