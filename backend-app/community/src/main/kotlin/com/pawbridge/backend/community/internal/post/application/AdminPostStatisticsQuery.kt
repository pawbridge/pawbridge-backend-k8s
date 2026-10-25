package com.pawbridge.backend.community.internal.post.application

import java.time.LocalDate

interface AdminPostStatisticsQuery {
    fun today(): Long

    fun period(startDate: LocalDate?, endDate: LocalDate?): PostPeriodStatsResponse

    @JvmRecord
    data class DailyPostStats(val date: LocalDate, val count: Long)

    @JvmRecord
    data class BoardTypeStats(val boardType: String?, val count: Long)

    @JvmRecord
    data class PostPeriodStatsResponse(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val daily: List<DailyPostStats>,
        val previousDayCount: Long,
        val byBoardType: List<BoardTypeStats>,
    )
}
