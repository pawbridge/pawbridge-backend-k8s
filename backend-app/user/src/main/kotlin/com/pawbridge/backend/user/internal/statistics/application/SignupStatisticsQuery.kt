package com.pawbridge.backend.user.internal.statistics.application

import java.time.LocalDate

interface SignupStatisticsQuery {
    fun daily(startDate: LocalDate, endDate: LocalDate): List<DailySignupStats>

    fun periods(): SignupPeriods

    // The Java record accessors (`date()`, `count()`) stay for Java callers; the standard getters keep the JSON shape.
    data class DailySignupStats(
        val date: LocalDate?,
        val count: Long?,
    ) {
        fun date(): LocalDate? = date

        fun count(): Long? = count
    }

    // The Java record accessors (`today()`, ...) stay for Java callers; the standard getters keep the JSON shape.
    data class SignupPeriods(
        val today: List<DailySignupStats>?,
        val last7Days: List<DailySignupStats>?,
        val last30Days: List<DailySignupStats>?,
        val thisMonth: List<DailySignupStats>?,
    ) {
        fun today(): List<DailySignupStats>? = today

        fun last7Days(): List<DailySignupStats>? = last7Days

        fun last30Days(): List<DailySignupStats>? = last30Days

        fun thisMonth(): List<DailySignupStats>? = thisMonth
    }
}
