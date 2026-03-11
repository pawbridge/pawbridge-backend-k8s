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
     * 2. 서버: shelterAddress.split(" ")[0] → normalizeRegion() → 합산
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

        // 2. shelterAddress 정제 (normalizeRegion) 후 합산
        Map<String, Long> regionMap = new LinkedHashMap<>();
        for (ShelterRescueCountResponse stat : shelterStats) {
            String address = stat.getShelterAddress();
            if (address == null || address.isBlank()) {
                log.warn("주소가 없는 보호소 데이터 스킵 (count={})", stat.getCount());
                continue;
            }
            // "경상남도 창원시..." → "경상남도" → "경남"
            String rawRegion = address.split(" ")[0]; 
            String normalizedRegion = normalizeRegion(rawRegion);
            regionMap.merge(normalizedRegion, stat.getCount(), Long::sum);
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
     * 시/도 문자열을 표준 형태(두 글자)로 정제
     * 예: "서울특별시" -> "서울", "제주특별자치도" -> "제주"
     */
    private String normalizeRegion(String rawRegion) {
        if (rawRegion == null) return "기타";
        
        if (rawRegion.contains("서울")) return "서울";
        if (rawRegion.contains("부산")) return "부산";
        if (rawRegion.contains("대구")) return "대구";
        if (rawRegion.contains("인천")) return "인천";
        if (rawRegion.contains("광주")) return "광주";
        if (rawRegion.contains("대전")) return "대전";
        if (rawRegion.contains("울산")) return "울산";
        if (rawRegion.contains("세종")) return "세종";
        if (rawRegion.contains("경기")) return "경기";
        if (rawRegion.contains("강원")) return "강원";
        if (rawRegion.contains("충청북도") || rawRegion.contains("충북")) return "충북";
        if (rawRegion.contains("충청남도") || rawRegion.contains("충남")) return "충남";
        if (rawRegion.contains("전라북도") || rawRegion.contains("전북")) return "전북";
        if (rawRegion.contains("전라남도") || rawRegion.contains("전남")) return "전남";
        if (rawRegion.contains("경상북도") || rawRegion.contains("경북")) return "경북";
        if (rawRegion.contains("경상남도") || rawRegion.contains("경남")) return "경남";
        if (rawRegion.contains("제주")) return "제주";
        
        return rawRegion; // 매핑되지 않은 경우 원본 반환
    }

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
