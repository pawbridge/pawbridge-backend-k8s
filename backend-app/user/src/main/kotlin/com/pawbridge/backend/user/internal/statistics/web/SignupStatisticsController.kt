package com.pawbridge.backend.user.internal.statistics.web

import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.DailySignupStats
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.SignupPeriods
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
class SignupStatisticsController(private val statistics: SignupStatisticsQuery) {

    @GetMapping("/api/v1/admin/users/stats/daily-signups")
    fun daily(
        @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ): TotalUserCountController.Response<List<DailySignupStats>> =
        TotalUserCountController.Response(200, "요청이 성공적으로 처리되었습니다.", statistics.daily(startDate, endDate))

    @GetMapping("/api/v1/admin/users/stats/signup-periods")
    fun periods(): TotalUserCountController.Response<SignupPeriods> =
        TotalUserCountController.Response(200, "요청이 성공적으로 처리되었습니다.", statistics.periods())
}
