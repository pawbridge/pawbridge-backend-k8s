package com.pawbridge.backend.user.internal.statistics.application

import java.time.LocalDate

interface SignupStatisticsQuery {
    fun daily(startDate: LocalDate, endDate: LocalDate): List<DailySignupStats>

    fun periods(): SignupPeriods

    @JvmRecord
    data class DailySignupStats(
        val date: LocalDate?,
        val count: Long?,
    )

    @JvmRecord
    data class SignupPeriods(
        val today: List<DailySignupStats>?,
        val last7Days: List<DailySignupStats>?,
        val last30Days: List<DailySignupStats>?,
        val thisMonth: List<DailySignupStats>?,
    )
}
