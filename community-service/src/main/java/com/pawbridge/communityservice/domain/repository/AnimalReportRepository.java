package com.pawbridge.communityservice.domain.repository;

import com.pawbridge.communityservice.domain.entity.AnimalReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AnimalReportRepository extends JpaRepository<AnimalReport, Long> {
    Optional<AnimalReport> findByReportIdAndDeletedAtIsNull(Long reportId);

    @Query("""
            SELECT r FROM AnimalReport r
            WHERE r.deletedAt IS NULL
              AND (:kind IS NULL OR r.kind = :kind)
              AND (:keyword = '' OR LOCATE(:keyword, LOWER(r.description)) > 0
                OR LOCATE(:keyword, LOWER(r.region)) > 0
                OR LOCATE(:keyword, LOWER(r.species)) > 0
                OR LOCATE(:keyword, LOWER(r.animalName)) > 0
                OR LOCATE(:keyword, LOWER(r.distinguishingFeatures)) > 0)
            """)
    Page<AnimalReport> searchVisible(@Param("kind") AnimalReport.Kind kind,
                                     @Param("keyword") String keyword, Pageable pageable);
}
