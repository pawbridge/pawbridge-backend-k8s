package com.pawbridge.communityservice.domain.repository;

import com.pawbridge.communityservice.domain.entity.AnimalReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnimalReportRepository extends JpaRepository<AnimalReport, Long> {
}
