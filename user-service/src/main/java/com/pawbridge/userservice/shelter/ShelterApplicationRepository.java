package com.pawbridge.userservice.shelter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShelterApplicationRepository extends JpaRepository<ShelterApplication, Long> {
    java.util.Optional<ShelterApplication> findFirstByUserIdAndCareRegNoAndStatusOrderByIdDesc(
            Long userId, String careRegNo, ShelterApplicationStatus status);

    boolean existsByUserIdAndStatus(Long userId, ShelterApplicationStatus status);
    Page<ShelterApplication> findByUserId(Long userId, Pageable pageable);
    Page<ShelterApplication> findByStatus(ShelterApplicationStatus status, Pageable pageable);
}
