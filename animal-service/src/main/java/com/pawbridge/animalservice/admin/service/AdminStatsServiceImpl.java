package com.pawbridge.animalservice.admin.service;

import com.pawbridge.animalservice.admin.dto.DailyAnimalStatsResponse;
import com.pawbridge.animalservice.admin.repository.AdminStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 통계 서비스 구현체
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminStatsServiceImpl implements AdminStatsService {

    private final AdminStatsRepository adminStatsRepository;

    @Override
    @Transactional(readOnly = true)
    public List<DailyAnimalStatsResponse> getDailyAnimalStats(LocalDate startDate, LocalDate endDate) {
        log.info("일일 동물 등록 건수 통계 조회: startDate={}, endDate={}", startDate, endDate);

        List<DailyAnimalStatsResponse> stats = adminStatsRepository.countDailyAnimals(startDate, endDate);
        log.info("일일 동물 등록 건수 통계 조회 완료: {} 건", stats.size());

        return stats;
    }
}
