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
     * 오늘 구조(공고)된 마릿수 (noticeStartDate 기준)
     *
     * @param today 오늘 날짜
     * @return 구조 마릿수
     */
    @Query("SELECT COUNT(a) FROM Animal a WHERE a.noticeStartDate = :today")
    Long countRescuedToday(@Param("today") LocalDate today);

    /**
     * 오늘 입양된 마릿수
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
     * - 상태(status)가 변경된 날짜(apmsUpdatedAt) 기준 집계
     *
     * @param startDate 시작일
     * @param endDate   종료일
     * @return 상태별 건수 목록
     */
    @Query("SELECT new com.pawbridge.animalservice.dto.response.StatusStatsResponse(" +
           "a.status, COUNT(a)) " +
           "FROM Animal a " +
           "WHERE CAST(a.apmsUpdatedAt AS LocalDate) BETWEEN :startDate AND :endDate " +
           "GROUP BY a.status " +
           "ORDER BY COUNT(a) DESC")
    List<StatusStatsResponse> countByStatus(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * 기간별 보호소별 구조(공고) 건수 (지역별 집계용)
     * - DB에서 보호소 수(~200행)만 반환
     * - 이후 Service에서 address 첫 단어로 시/도별 합산
     * - 구조/공고 기준일(noticeStartDate) 기준
     *
     * @param startDate 시작일
     * @param endDate   종료일
     * @return 보호소별 (주소, 건수) 목록
     */
    @Query("SELECT new com.pawbridge.animalservice.dto.response.ShelterRescueCountResponse(" +
           "s.address, COUNT(a)) " +
           "FROM Animal a JOIN a.shelter s " +
           "WHERE a.noticeStartDate BETWEEN :startDate AND :endDate " +
           "GROUP BY s.id " +
           "ORDER BY COUNT(a) DESC")
    List<ShelterRescueCountResponse> countByShelterForRegional(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
