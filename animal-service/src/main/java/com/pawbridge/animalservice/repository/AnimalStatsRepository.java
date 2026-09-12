package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.dto.response.ShelterRescueCountResponse;
import com.pawbridge.animalservice.dto.response.StatusStatsResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 통계 전용 Repository
 * - 공개 통계 API용 집계 쿼리만 포함
 * - 기존 AnimalRepository와 분리 (SRP)
 */
@Repository
public interface AnimalStatsRepository extends JpaRepository<Animal, Long> {

    /**
     * 오늘 구조된 마릿수 (APMS 접수일 happenDate 기준)
     *
     * @param today 오늘 날짜
     * @return 구조 마릿수
     */
    @Query("SELECT COUNT(a) FROM Animal a WHERE a.happenDate = :today")
    Long countRescuedToday(@Param("today") LocalDate today);

    /**
     * 오늘 APMS 정보가 수정된 동물 중 현재 입양 상태인 마릿수 (입양 발생일 아님)
     * - status = ADOPTED AND DATE(apmsUpdatedAt) = 오늘
     *
     * @param today  오늘 날짜
     * @param status ADOPTED 상태
     * @return 입양 마릿수
     */
    @Query("SELECT COUNT(a) FROM Animal a " +
           "WHERE a.status = :status " +
           "AND CAST(a.apmsUpdatedAt AS LocalDate) = :today")
    Long countAdoptedToday(@Param("today") LocalDate today,
                           @Param("status") AnimalStatus status);

    /**
     * 기간별 상태별 현황
     * - 선택 기간에 구조된 동물의 현재 상태를 집계 (happenDate 기준)
     *
     * @param startDate 시작일
     * @param endDate   종료일
     * @return 상태별 건수 목록
     */
    @Query("SELECT new com.pawbridge.animalservice.dto.response.StatusStatsResponse(" +
           "a.status, COUNT(a)) " +
           "FROM Animal a " +
           "WHERE a.happenDate BETWEEN :startDate AND :endDate " +
           "GROUP BY a.status " +
           "ORDER BY COUNT(a) DESC")
    List<StatusStatsResponse> countByStatus(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 기간별 보호소별 구조 건수 (지역별 집계용)
     * - DB에서 보호소 수(~200행)만 반환
     * - 이후 Service에서 address 첫 단어로 시/도별 합산
     * - APMS 접수일(happenDate) 기준
     *
     * @param startDate 시작일
     * @param endDate   종료일
     * @return 보호소별 (주소, 건수) 목록
     */
    @Query("SELECT new com.pawbridge.animalservice.dto.response.ShelterRescueCountResponse(" +
           "s.address, COUNT(a)) " +
           "FROM Animal a JOIN a.shelter s " +
           "WHERE a.happenDate BETWEEN :startDate AND :endDate " +
           "GROUP BY s.id " +
           "ORDER BY COUNT(a) DESC")
    List<ShelterRescueCountResponse> countByShelterForRegional(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
