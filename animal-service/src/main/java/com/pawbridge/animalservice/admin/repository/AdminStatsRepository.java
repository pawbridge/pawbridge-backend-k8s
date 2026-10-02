package com.pawbridge.animalservice.admin.repository;

import com.pawbridge.animalservice.admin.dto.DailyAnimalStatsResponse;
import com.pawbridge.animalservice.entity.Animal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 통계 전용 Repository
 * - Animal 엔티티를 사용하지만 통계 쿼리만 포함
 */
@Repository
public interface AdminStatsRepository extends JpaRepository<Animal, Long> {

    @Query("SELECT new com.pawbridge.animalservice.admin.dto.DailyAnimalStatsResponse(" +
            "a.happenDate, COUNT(a)) FROM Animal a " +
            "WHERE a.happenDate BETWEEN :startDate AND :endDate " +
            "GROUP BY a.happenDate ORDER BY a.happenDate")
    List<DailyAnimalStatsResponse> countDailyIntakes(
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * 한국 날짜 기준 일일 동물 등록 건수 통계 (UTC 저장 시각에 9시간 적용)
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 일별 동물 등록 건수 목록
     */
    @Query("SELECT new com.pawbridge.animalservice.admin.dto.DailyAnimalStatsResponse(" +
           "CAST(a.createdAt + 9 hour AS LocalDate), COUNT(a)) " +
           "FROM Animal a " +
           "WHERE CAST(a.createdAt + 9 hour AS LocalDate) BETWEEN :startDate AND :endDate " +
           "GROUP BY CAST(a.createdAt + 9 hour AS LocalDate) " +
           "ORDER BY CAST(a.createdAt + 9 hour AS LocalDate)")
    List<DailyAnimalStatsResponse> countDailyAnimals(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
