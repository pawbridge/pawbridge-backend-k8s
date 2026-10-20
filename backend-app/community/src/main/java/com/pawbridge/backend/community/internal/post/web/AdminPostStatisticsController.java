package com.pawbridge.backend.community.internal.post.web;

import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery;
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.PostPeriodStatsResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/posts/stats")
public final class AdminPostStatisticsController {
    private final AdminPostStatisticsQuery statistics;
    public AdminPostStatisticsController(AdminPostStatisticsQuery statistics) { this.statistics = statistics; }

    @GetMapping("/today")
    public ApiResponse<Long> today() { return ok(statistics.today()); }

    @GetMapping("/period")
    public ApiResponse<PostPeriodStatsResponse> period(
            @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ok(statistics.period(startDate, endDate));
    }

    private static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "요청이 성공적으로 처리되었습니다.", data);
    }
}
