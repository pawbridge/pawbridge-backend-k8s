package com.pawbridge.animalservice.admin.service;

import com.pawbridge.animalservice.admin.dto.DailyAnimalStatsResponse;
import com.pawbridge.animalservice.admin.dto.IntakeTrendResponse;
import com.pawbridge.animalservice.admin.repository.AdminStatsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 관리자 통계 서비스 구현체
 */
@Slf4j
@Service
public class AdminStatsServiceImpl implements AdminStatsService {

    private final AdminStatsRepository adminStatsRepository;
    private final Clock clock;

    @Autowired
    public AdminStatsServiceImpl(AdminStatsRepository repository) {
        this(repository, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    AdminStatsServiceImpl(AdminStatsRepository repository, Clock clock) {
        this.adminStatsRepository = repository;
        this.clock = clock.withZone(ZoneId.of("Asia/Seoul"));
    }

    @Override
    @Transactional(readOnly = true)
    public IntakeTrendResponse getIntakeTrend(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)
                || endDate.isAfter(LocalDate.now(clock))
                || ChronoUnit.DAYS.between(startDate, endDate) >= 366) {
            throw new IllegalArgumentException("오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.");
        }
        LocalDate previousDate = startDate.minusDays(1);
        List<DailyAnimalStatsResponse> rows = adminStatsRepository.countDailyIntakes(previousDate, endDate);
        long previous = rows.stream().filter(row -> row.getDate().equals(previousDate))
                .mapToLong(DailyAnimalStatsResponse::getCount).sum();
        return new IntakeTrendResponse(startDate, endDate,
                rows.stream().filter(row -> !row.getDate().isBefore(startDate)).toList(), previous);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyAnimalStatsResponse> getDailyAnimalStats(LocalDate startDate, LocalDate endDate) {
        log.info("일일 동물 등록 건수 통계 조회: startDate={}, endDate={}", startDate, endDate);

        List<DailyAnimalStatsResponse> stats = adminStatsRepository.countDailyAnimals(startDate, endDate);
        log.info("일일 동물 등록 건수 통계 조회 완료: {} 건", stats.size());

        return stats;
    }
}
