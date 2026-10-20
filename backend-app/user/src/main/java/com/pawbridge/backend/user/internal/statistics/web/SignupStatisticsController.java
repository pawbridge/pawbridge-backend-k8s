package com.pawbridge.backend.user.internal.statistics.web;

import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery;
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.DailySignupStats;
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.SignupPeriods;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SignupStatisticsController {
    private final SignupStatisticsQuery statistics;

    public SignupStatisticsController(SignupStatisticsQuery statistics) {
        this.statistics = statistics;
    }

    @GetMapping("/api/v1/admin/users/stats/daily-signups")
    public TotalUserCountController.Response<List<DailySignupStats>> daily(
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return new TotalUserCountController.Response<>(200, "요청이 성공적으로 처리되었습니다.", statistics.daily(startDate, endDate));
    }

    @GetMapping("/api/v1/admin/users/stats/signup-periods")
    public TotalUserCountController.Response<SignupPeriods> periods() {
        return new TotalUserCountController.Response<>(200, "요청이 성공적으로 처리되었습니다.", statistics.periods());
    }
}
