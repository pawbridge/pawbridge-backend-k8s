package com.pawbridge.backend.community.internal.post.web

import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.PostPeriodStatsResponse
import java.time.LocalDate
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/posts/stats")
class AdminPostStatisticsController(private val statistics: AdminPostStatisticsQuery) {

    @GetMapping("/today")
    fun today(): ApiResponse<Long> = ok(statistics.today())

    @GetMapping("/period")
    fun period(
        @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ): ApiResponse<PostPeriodStatsResponse> = ok(statistics.period(startDate, endDate))

    private fun <T> ok(data: T): ApiResponse<T> = ApiResponse(200, "요청이 성공적으로 처리되었습니다.", data)
}
