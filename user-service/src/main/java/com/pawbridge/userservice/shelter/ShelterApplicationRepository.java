package com.pawbridge.userservice.shelter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface ShelterApplicationRepository extends JpaRepository<ShelterApplication, Long> {
    java.util.Optional<ShelterApplication> findFirstByUserIdAndCareRegNoAndStatusOrderByIdDesc(
            Long userId, String careRegNo, ShelterApplicationStatus status);

    boolean existsByUserIdAndStatus(Long userId, ShelterApplicationStatus status);
    Page<ShelterApplication> findByUserId(Long userId, Pageable pageable);
    Page<ShelterApplication> findByStatus(ShelterApplicationStatus status, Pageable pageable);

    // PostgreSQL profile stores Seoul LocalDateTime using UTC JDBC. Group the stored
    // UTC wall clock as a Korean calendar day; typed bounds retain Hibernate UTC binding.
    @Query("SELECT new com.pawbridge.userservice.shelter.DailyShelterApplicationStats("
            + "cast(a.requestedAt + 9 hour as LocalDate), COUNT(a)) FROM ShelterApplication a "
            + "WHERE a.requestedAt >= :start AND a.requestedAt < :end "
            + "GROUP BY cast(a.requestedAt + 9 hour as LocalDate) ORDER BY cast(a.requestedAt + 9 hour as LocalDate)")
    List<DailyShelterApplicationStats> countDailyRequests(@Param("start") LocalDateTime start,
                                                         @Param("end") LocalDateTime end);

    @Query("SELECT new com.pawbridge.userservice.shelter.ShelterReviewStats(a.status, COUNT(a)) "
            + "FROM ShelterApplication a WHERE a.reviewedAt >= :start AND a.reviewedAt < :end "
            + "AND a.status IN (com.pawbridge.userservice.shelter.ShelterApplicationStatus.APPROVED, "
            + "com.pawbridge.userservice.shelter.ShelterApplicationStatus.REJECTED) GROUP BY a.status")
    List<ShelterReviewStats> countReviews(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    long countByStatus(ShelterApplicationStatus status);
}
