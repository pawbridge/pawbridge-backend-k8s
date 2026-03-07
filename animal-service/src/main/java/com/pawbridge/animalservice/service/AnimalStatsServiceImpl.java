package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.response.RegionalRescueStatsResponse;
import com.pawbridge.animalservice.dto.response.ShelterRescueCountResponse;
import com.pawbridge.animalservice.dto.response.StatusStatsResponse;
import com.pawbridge.animalservice.dto.response.TodayStatsResponse;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.repository.AnimalStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 공개 통계 서비스 구현체
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnimalStatsServiceImpl implements AnimalStatsService {

    private final AnimalStatsRepository animalStatsRepository;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API 1: 오늘의 통계
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 오늘 구조 마릿수 + 입양 마릿수 반환
     * - TZ env 설정으로 JVM 자체가 KST이므로 LocalDate.now() 그대로 사용
     */
    @Override
    @Transactional(readOnly = true)
    public TodayStatsResponse getTodayStats() {
        LocalDate today = LocalDate.now();
        log.info("오늘의 통계 조회: date={}", today);

        Long rescuedToday = animalStatsRepository.countRescuedToday(today);
        Long adoptedToday = animalStatsRepository.countAdoptedToday(today, AnimalStatus.ADOPTED);

        log.info("오늘의 통계 완료: rescued={}, adopted={}", rescuedToday, adoptedToday);
        return new TodayStatsResponse(today, rescuedToday, adoptedToday);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API 2: 상태별 현황
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 기간별 상태별 현황
     * - startDate/endDate null 시 최근 30일 기본값 적용
     */
    @Override
    @Transactional(readOnly = true)
    public List<StatusStatsResponse> getStatusStats(LocalDate startDate, LocalDate endDate) {
        LocalDate[] range = resolveDefaultDateRange(startDate, endDate);
        log.info("상태별 통계 조회: startDate={}, endDate={}", range[0], range[1]);

        List<StatusStatsResponse> stats = animalStatsRepository.countByStatus(range[0], range[1]);
        log.info("상태별 통계 완료: {} 건", stats.size());
        return stats;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API 3: 지역별 통계
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 기간별 지역별 구조 통계 (DB + 서버 협업)
     *
     * <처리 흐름>
     * 1. DB: Animal JOIN Shelter → GROUP BY shelter.id → ~200행 반환
     * 2. 서버: shelterAddress.split(" ")[0] → 시/도 추출 → 합산
     * 3. 결과: 17개 시/도 기준 정렬해서 반환
     *
     * - startDate/endDate null 시 최근 30일 기본값 적용
     */
    @Override
    @Transactional(readOnly = true)
    public List<RegionalRescueStatsResponse> getRegionalStats(LocalDate startDate, LocalDate endDate) {
        LocalDate[] range = resolveDefaultDateRange(startDate, endDate);
        log.info("지역별 통계 조회: startDate={}, endDate={}", range[0], range[1]);

        // 1. DB에서 보호소별 결과 조회 (~200행)
        List<ShelterRescueCountResponse> shelterStats =
                animalStatsRepository.countByShelterForRegional(range[0], range[1]);

        // 2. shelterAddress 첫 단어로 시/도 추출 후 합산
        Map<String, Long> regionMap = new LinkedHashMap<>();
        for (ShelterRescueCountResponse stat : shelterStats) {
            String address = stat.getShelterAddress();
            if (address == null || address.isBlank()) {
                log.warn("주소가 없는 보호소 데이터 스킵 (count={})", stat.getCount());
                continue;
            }
            String region = address.split(" ")[0]; // "경상남도 창원시..." → "경상남도"
            regionMap.merge(region, stat.getCount(), Long::sum);
        }

        // 3. count 내림차순 정렬 후 최종 DTO로 변환
        List<RegionalRescueStatsResponse> result = regionMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new RegionalRescueStatsResponse(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        log.info("지역별 통계 완료: {} 개 시/도", result.size());
        return result;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 공통 유틸
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * startDate/endDate null 시 최근 30일로 기본값 처리
     * - TZ env 설정으로 JVM이 KST이므로 LocalDate.now() 그대로 사용
     *
     * @return [startDate, endDate]
     */
    private LocalDate[] resolveDefaultDateRange(LocalDate startDate, LocalDate endDate) {
        LocalDate resolvedEnd = (endDate != null) ? endDate : LocalDate.now();
        LocalDate resolvedStart = (startDate != null) ? startDate : resolvedEnd.minusDays(30);
        return new LocalDate[]{resolvedStart, resolvedEnd};
    }
}
