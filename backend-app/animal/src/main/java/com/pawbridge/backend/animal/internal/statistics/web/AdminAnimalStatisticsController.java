package com.pawbridge.backend.animal.internal.statistics.web;

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Root must apply ADMIN authorization before registering these handlers. */
@RestController
public final class AdminAnimalStatisticsController {
    private final AnimalStatistics statistics;
    public AdminAnimalStatisticsController(AnimalStatistics statistics) { this.statistics = statistics; }

    @GetMapping("/api/v1/admin/stats/intake-trend")
    public AnimalStatistics.IntakeTrend intakeTrend(
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return statistics.intakeTrend(startDate, endDate);
    }

    @GetMapping("/api/v1/admin/stats/daily-animals")
    public List<AnimalStatistics.DailyCount> dailyAnimals(
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return statistics.dailyAnimals(startDate, endDate);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Void> invalidDate() { return ResponseEntity.badRequest().build(); }
}
