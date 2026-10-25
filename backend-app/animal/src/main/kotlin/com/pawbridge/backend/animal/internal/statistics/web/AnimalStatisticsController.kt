package com.pawbridge.backend.animal.internal.statistics.web

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** Parent security registers only these three exact public GETs. */
@RestController
class AnimalStatisticsController(
    private val statistics: AnimalStatistics,
) {

    @GetMapping("/api/v1/animals/stats/today")
    fun today(): AnimalStatistics.Today = statistics.today()

    @GetMapping("/api/v1/animals/stats/status")
    fun status(
        @RequestParam(name = "startDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        startDate: LocalDate?,
        @RequestParam(name = "endDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        endDate: LocalDate?,
    ): List<StatusResponse> {
        return statistics.status(startDate, endDate)
            .map { value -> StatusResponse(value.status, value.count, value.label()) }
    }

    @GetMapping("/api/v1/animals/stats/regional")
    fun regional(
        @RequestParam(name = "startDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        startDate: LocalDate?,
        @RequestParam(name = "endDate", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        endDate: LocalDate?,
    ): List<AnimalStatistics.RegionalCount> {
        return statistics.regional(startDate, endDate)
    }

    @JvmRecord
    data class StatusResponse(
        val status: AnimalStatistics.Status?,
        val count: Long?,
        val label: String?,
    )
}
