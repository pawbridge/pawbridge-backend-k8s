package com.pawbridge.communityservice.domain.repository;

import com.pawbridge.communityservice.domain.entity.AnimalReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import jakarta.persistence.criteria.Predicate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;

public interface AnimalReportRepository extends JpaRepository<AnimalReport, Long>, JpaSpecificationExecutor<AnimalReport> {
    Optional<AnimalReport> findByReportIdAndDeletedAtIsNull(Long reportId);

    default Page<AnimalReport> searchVisible(AnimalReport.Kind kind, String keyword, Pageable pageable) {
        return searchVisible(kind, keyword, null, null, null, null, null, pageable);
    }

    default Page<AnimalReport> searchVisible(AnimalReport.Kind kind, String keyword,
                                            String province, String district, AnimalReport.AnimalType animalType,
                                            LocalDate from, LocalDate to, Pageable pageable) {
        return findAll((root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (kind != null) predicates.add(cb.equal(root.get("kind"), kind));
            if (province != null) predicates.add(cb.equal(root.get("province"), province));
            if (district != null) predicates.add(cb.equal(root.get("district"), district));
            if (animalType != null) predicates.add(cb.equal(root.get("animalType"), animalType));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("occurredOn"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("occurredOn"), to));
            if (!keyword.isEmpty()) {
                predicates.add(cb.or(
                        cb.gt(cb.locate(cb.lower(root.get("description")), keyword), 0),
                        cb.gt(cb.locate(cb.lower(root.get("region")), keyword), 0),
                        cb.gt(cb.locate(cb.lower(root.get("species")), keyword), 0),
                        cb.gt(cb.locate(cb.lower(root.get("animalName")), keyword), 0),
                        cb.gt(cb.locate(cb.lower(root.get("distinguishingFeatures")), keyword), 0)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
    }
}
