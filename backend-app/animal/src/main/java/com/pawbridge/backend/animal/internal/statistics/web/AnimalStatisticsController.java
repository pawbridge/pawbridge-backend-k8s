package com.pawbridge.backend.animal.internal.statistics.web;

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Parent security registers only these three exact public GETs. */
@RestController
public final class AnimalStatisticsController {
    private final AnimalStatistics statistics;
    public AnimalStatisticsController(AnimalStatistics statistics) { this.statistics = statistics; }

    @GetMapping("/api/v1/animals/stats/today")
    public AnimalStatistics.Today today() { return statistics.today(); }

    @GetMapping("/api/v1/animals/stats/status")
    public List<StatusResponse> status(
            @RequestParam(name = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return statistics.status(startDate, endDate).stream().map(value -> new StatusResponse(value.status(), value.count(), value.label())).toList();
    }

    @GetMapping("/api/v1/animals/stats/regional")
    public List<AnimalStatistics.RegionalCount> regional(
            @RequestParam(name = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return statistics.regional(startDate, endDate);
    }

    public record StatusResponse(AnimalStatistics.Status status, Long count, String label) { }
}
