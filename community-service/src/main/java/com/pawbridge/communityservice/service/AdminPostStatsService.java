package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.dto.response.DailyPostStats;
import com.pawbridge.communityservice.dto.response.PostPeriodStatsResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminPostStatsService {
    private final PostRepository posts;
    private final Clock clock;

    @Autowired
    public AdminPostStatsService(PostRepository posts) {
        this(posts, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    AdminPostStatsService(PostRepository posts, Clock clock) {
        this.posts = posts;
        this.clock = clock.withZone(ZoneId.of("Asia/Seoul"));
    }

    public PostPeriodStatsResponse period(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)
                || endDate.isAfter(LocalDate.now(clock)) || ChronoUnit.DAYS.between(startDate, endDate) >= 366) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.");
        }
        LocalDate previousDate = startDate.minusDays(1);
        var rows = posts.countDailyPosts(previousDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
        return new PostPeriodStatsResponse(startDate, endDate,
                rows.stream().filter(row -> !row.date().isBefore(startDate)).toList(),
                rows.stream().filter(row -> row.date().equals(previousDate)).mapToLong(DailyPostStats::count).sum(),
                posts.countByBoardType(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay()));
    }
}
