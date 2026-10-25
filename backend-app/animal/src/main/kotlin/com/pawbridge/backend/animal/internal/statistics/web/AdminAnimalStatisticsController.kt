package com.pawbridge.backend.animal.internal.statistics.web

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.LocalDate

/** Root must apply ADMIN authorization before registering these handlers. */
@RestController
class AdminAnimalStatisticsController(
    private val statistics: AnimalStatistics,
) {

    @GetMapping("/api/v1/admin/stats/intake-trend")
    fun intakeTrend(
        @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ): AnimalStatistics.IntakeTrend {
        return statistics.intakeTrend(startDate, endDate)
    }

    @GetMapping("/api/v1/admin/stats/daily-animals")
    fun dailyAnimals(
        @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate,
    ): List<AnimalStatistics.DailyCount> {
        return statistics.dailyAnimals(startDate, endDate)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidDate(): ResponseEntity<Void> = ResponseEntity.badRequest().build()
}
